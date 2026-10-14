package com.pnomeer.lab.experiments.career;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.runtime.*;
import com.pnomeer.lab.worker.WireJson;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Controller-only sequence provisioning. No resource choice or credentials from scenario input. */
public final class SequenceContainerResources implements ContainerWorkerResources {
    private final String mysqlImage;
    public SequenceContainerResources(String mysqlImage) {
        if (mysqlImage == null || !mysqlImage.matches("sha256:[a-f0-9]{64}")) throw new IllegalArgumentException("Pinned MySQL image required");
        this.mysqlImage = mysqlImage;
    }
    @Override public Session open(ObjectNode command, Path workspace, String artifactSha256) throws Exception {
        if (!command.path("experimentId").asText().equals("career.mcp-platform.sequence")) throw new IllegalArgumentException("Sequence resources only");
        if (!command.path("runId").asText().matches("[a-zA-Z0-9._-]{1,200}") || artifactSha256 == null || !artifactSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid resource identity");
        return new Resources(command, Files.createDirectory(workspace.resolve("sequence-resources")), artifactSha256);
    }
    private final class Resources implements Session {
        private final ObjectNode command;
        private final String artifactSha256;
        private final Path directory;
        private final String token = UUID.randomUUID().toString(), password = secret(), rootPassword = secret();
        private final String name = "engineering-lab-sequence-net-" + token;
        private String engineId, networkId, intentHash;
        private boolean attempted, finished;
        private WorkerContainerLifecycle database;
        Resources(ObjectNode command, Path directory, String artifactSha256) { this.command = command.deepCopy(); this.directory = directory; this.artifactSha256 = artifactSha256; }
        public void prepare() throws Exception {
            if (attempted || finished) throw new IOException("Resource session cannot be reused");
            engineId = new DockerContainerEngine().identity();
            if (!engineId.matches("[a-zA-Z0-9:-]{1,200}")) throw new IOException("Invalid Docker engine identity");
            var intent = WireJson.object().put("version", 1).put("profile", "sequence-network-v1")
                    .put("engineId", engineId).put("token", token).put("name", name).put("mysqlImage", mysqlImage)
                    .put("runId", command.path("runId").asText()).put("artifactSha256", artifactSha256);
            byte[] bytes = WireJson.encode(intent); write("intent", bytes); intentHash = hash(bytes); attempted = true;
            sameEngine();
            networkId = DockerContainerEngine.command("network", "create", "--driver", "bridge", "--internal",
                    "--label", DockerContainerEngine.OWNER + "=" + token, "--label", DockerContainerEngine.RUN + "=" + command.path("runId").asText(),
                    "--label", "engineering-lab.resource=sequence-network", name).trim();
            if (!networkId.matches("[a-f0-9]{64}")) { networkId = null; throw new IOException("Network create unconfirmed"); }
            verifyNetwork(); write("created", receipt().put("id", networkId));
            var mysql = new MysqlEngine(networkId, password, rootPassword);
            var spec = new WorkerContainerLifecycle.Spec(command.path("runId").asText(), artifactSha256, mysqlImage,
                    List.of("/usr/local/bin/docker-entrypoint.sh", "mysqld", "--skip-log-bin", "--innodb-buffer-pool-size=64M", "--performance-schema=OFF"));
            database = new WorkerContainerLifecycle(Files.createDirectory(directory.resolve("mysql")), spec, mysql);
            String id = database.create(); database.start();
            long deadline = Math.min(command.path("deadlineEpochMs").asLong(), System.currentTimeMillis() + 120000);
            while (true) {
                if (System.currentTimeMillis() >= deadline) throw new IOException("Sequence database readiness deadline");
                try {
                    String result = DockerContainerEngine.command("exec", "--env", "MYSQL_PWD=" + password, id,
                            "mysql", "--user=lab", "--database=sequence_lab", "--batch", "--skip-column-names", "--execute=SELECT 1");
                    if (result.trim().equals("1")) break;
                } catch (IOException ignored) { /* Read-only readiness retries; never repeat create/start. */ }
                Thread.sleep(200);
            }
            verifyNetwork();
        }
        public String networkId() { if (networkId == null) throw new IllegalStateException("Network not ready"); return networkId; }
        public Map<String, String> environment() {
            return Map.of("LAB_SEQUENCE_DB_MODE", "EXTERNAL", "LAB_SEQUENCE_DB_HOST", "sequence-db", "LAB_SEQUENCE_DB_PASSWORD", password);
        }
        public void finish(boolean workerRemoved) throws Exception {
            if (finished) throw new IOException("Resource finish already attempted"); finished = true;
            try {
                if (!workerRemoved) throw new IOException("Worker boundary still unconfirmed");
                if (database != null && database.reconcile() == WorkerContainerLifecycle.Recovery.REQUIRES_OPERATOR)
                    throw new IOException("Database boundary unconfirmed");
                if (!attempted) return;
                if (networkId == null || !Files.isRegularFile(directory.resolve("created.json"), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Network create unconfirmed");
                sameEngine(); verifyNetwork();
                var network = network();
                if (!network.path("Containers").isObject() || !network.path("Containers").isEmpty()) throw new IOException("Network still has endpoints");
                write("removing", receipt());
                DockerContainerEngine.command("network", "rm", networkId);
                sameEngine();
                if (!DockerContainerEngine.command("network", "ls", "--no-trunc", "--quiet", "--filter", "id=" + networkId).isBlank())
                    throw new IOException("Network removal unconfirmed");
                sameEngine(); write("removed", receipt());
            } finally { if (database != null) database.close(); }
        }
        private void sameEngine() throws Exception {
            if (!Objects.equals(engineId, new DockerContainerEngine().identity())) throw new IOException("Docker engine changed");
        }
        private ObjectNode network() throws Exception {
            return WireJson.parse(DockerContainerEngine.command("network", "inspect", "--format", "{{json .}}", networkId).getBytes(StandardCharsets.UTF_8));
        }
        private void verifyNetwork() throws Exception {
            sameEngine(); var value = network();
            if (!networkId.equals(value.path("Id").asText()) || !name.equals(value.path("Name").asText())
                    || !value.path("Internal").asBoolean() || value.path("Attachable").asBoolean()
                    || !"bridge".equals(value.path("Driver").asText())
                    || !"sequence-network".equals(value.path("Labels").path("engineering-lab.resource").asText())
                    || !token.equals(value.path("Labels").path(DockerContainerEngine.OWNER).asText())
                    || !command.path("runId").asText().equals(value.path("Labels").path(DockerContainerEngine.RUN).asText()))
                throw new IOException("Network ownership changed");
        }
        private ObjectNode receipt() { return WireJson.object().put("intentSha256", intentHash); }
        private void write(String step, ObjectNode value) throws IOException { write(step, WireJson.encode(value)); }
        private void write(String step, byte[] bytes) throws IOException {
            if (bytes.length > 4096) throw new IOException("Resource journal limit");
            try (var channel = FileChannel.open(directory.resolve(step + ".json"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
        }
    }
    private static String secret() { return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""); }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static final class MysqlEngine extends DockerContainerEngine {
        private final String network, password, rootPassword;
        MysqlEngine(String network, String password, String rootPassword) { this.network = network; this.password = password; this.rootPassword = rootPassword; }
        @Override public String profile() { return "sequence-mysql-v1:" + network + ":" + hash((password + rootPassword).getBytes(StandardCharsets.UTF_8)); }
        @Override public String create(WorkerContainerLifecycle.Spec spec, WorkerContainerLifecycle.Owner owner) throws Exception {
            var args = new ArrayList<>(List.of("create", "--pull=never", "--name", owner.name(),
                    "--label", OWNER + "=" + owner.token(), "--label", RUN + "=" + spec.runId(), "--label", ARTIFACT + "=" + spec.artifactSha256(),
                    "--user=999:999", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--network", network, "--network-alias=sequence-db",
                    "--ipc=private", "--restart=no", "--memory=512m", "--cpus=1", "--pids-limit=128", "--log-driver=none",
                    "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=16m", "--tmpfs", "/var/lib/mysql:rw,nosuid,nodev,noexec,size=256m,uid=999,gid=999",
                    "--tmpfs", "/var/run/mysqld:rw,nosuid,nodev,noexec,size=1m,uid=999,gid=999",
                    "--env", "MYSQL_DATABASE=sequence_lab", "--env", "MYSQL_USER=lab", "--env", "MYSQL_PASSWORD=" + password,
                    "--env", "MYSQL_ROOT_PASSWORD=" + rootPassword, "--entrypoint", spec.command().getFirst(), spec.imageId()));
            args.addAll(spec.command().subList(1, spec.command().size())); return command(args.toArray(String[]::new)).trim();
        }
        @Override public boolean verifySnapshot(ObjectNode value, WorkerContainerLifecycle.Spec spec, WorkerContainerLifecycle.Owner owner, String id) {
            if (!networkMatches(value, network, "sequence-db")) return false;
            // Normalize only the explicitly allowed DB profile differences, then reuse the strict worker checks.
            var copy = value.deepCopy(); var config = (ObjectNode)copy.path("Config"); var host = (ObjectNode)copy.path("HostConfig");
            if (!"999:999".equals(config.path("User").asText()) || !network.equals(host.path("NetworkMode").asText())
                    || host.path("Memory").asLong() != 512L * 1024 * 1024 || host.path("PidsLimit").asInt() != 128) return false;
            Set<String> env = new HashSet<>(); config.path("Env").forEach(v -> env.add(v.asText()));
            if (!env.containsAll(Set.of("MYSQL_DATABASE=sequence_lab", "MYSQL_USER=lab", "MYSQL_PASSWORD=" + password, "MYSQL_ROOT_PASSWORD=" + rootPassword))) return false;
            var tmpfs = host.path("Tmpfs");
            if (tmpfs.size() != 3 || !"rw,nosuid,nodev,noexec,size=256m,uid=999,gid=999".equals(tmpfs.path("/var/lib/mysql").asText())
                    || !"rw,nosuid,nodev,noexec,size=1m,uid=999,gid=999".equals(tmpfs.path("/var/run/mysqld").asText())) return false;
            for (var mount : copy.path("Mounts")) if (!"tmpfs".equals(mount.path("Type").asText())
                    || !Set.of("/tmp", "/var/lib/mysql", "/var/run/mysqld").contains(mount.path("Destination").asText())) return false;
            config.put("User", "65534:65534"); host.put("NetworkMode", "none").put("Memory", 256L * 1024 * 1024).put("PidsLimit", 64);
            ((ObjectNode)tmpfs).remove(List.of("/var/lib/mysql", "/var/run/mysqld")); copy.putArray("Mounts");
            return DockerContainerEngine.matches(copy, spec, owner, id);
        }
    }
}
