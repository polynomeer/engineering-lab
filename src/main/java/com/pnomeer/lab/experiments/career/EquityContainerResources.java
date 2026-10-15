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

/** Controller-only equity dependency provisioning. No resource choice or credentials from scenario input. */
public final class EquityContainerResources implements ContainerWorkerResources {
    @FunctionalInterface interface Commands { String run(String... arguments) throws Exception; }
    private final Commands docker;
    private final String serviceImage;
    private final boolean redis;
    private final String namespace;
    public EquityContainerResources(String image, boolean redis) { this(image, redis, DockerContainerEngine::command); }
    EquityContainerResources(String serviceImage, boolean redis, Commands docker) {
        if (serviceImage == null || !serviceImage.matches("sha256:[a-f0-9]{64}")) throw new IllegalArgumentException("Pinned dependency image required");
        this.redis = redis; this.namespace = redis ? "equity-redis" : "equity-mysql";
        this.serviceImage = serviceImage; this.docker = Objects.requireNonNull(docker);
    }
    @Override public String profile() { return namespace + "-v1:" + serviceImage; }
    @Override public Recovery recovery(Path workspace, String runId, String artifactSha256) throws Exception {
        Path directory = workspace.resolve(namespace + "-resources");
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return ContainerWorkerResources.NONE.recovery(workspace, runId, artifactSha256);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid resource directory");
        Path intentPath = directory.resolve("intent.json");
        if (Files.notExists(intentPath, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.list(directory)) { if (files.findAny().isPresent()) throw new IOException("Orphan resource records"); }
            return ContainerWorkerResources.NONE.recovery(workspace, runId, artifactSha256);
        }
        byte[] bytes = ContainerJournal.read(intentPath);
        var intent = WireJson.parse(bytes);
        String token = intent.path("token").asText(), engine = intent.path("engineId").asText();
        if (intent.size() != 8 || intent.path("version").asInt() != 1 || !(namespace + "-network-v1").equals(intent.path("profile").asText())
                || !serviceImage.equals(intent.path("serviceImage").asText()) || !runId.equals(intent.path("runId").asText())
                || !artifactSha256.equals(intent.path("artifactSha256").asText()) || !token.matches("[a-f0-9-]{36}")
                || !engine.matches("[a-zA-Z0-9:-]{1,200}") || !("engineering-lab-" + namespace + "-net-" + token).equals(intent.path("name").asText()))
            throw new IOException("Invalid network intent");
        String intentHash = ContainerJournal.hash(bytes);
        var created = ContainerJournal.json(directory.resolve("created.json")); // Missing/partial response is never eligible.
        String networkId = created.path("id").asText();
        if (created.size() != 2 || !intentHash.equals(created.path("intentSha256").asText()) || !networkId.matches("[a-f0-9]{64}"))
            throw new IOException("Invalid network receipt");
        boolean removing = !Files.notExists(directory.resolve("removing.json"), LinkOption.NOFOLLOW_LINKS);
        boolean removed = !Files.notExists(directory.resolve("removed.json"), LinkOption.NOFOLLOW_LINKS);
        if (removed && !removing) throw new IOException("Invalid network record order");
        for (String step : List.of("removing", "removed")) {
            Path path = directory.resolve(step + ".json");
            if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                var value = ContainerJournal.json(path);
                if (value.size() != 1 || !intentHash.equals(value.path("intentSha256").asText())) throw new IOException("Invalid network receipt");
            }
        }
        return new Recovery() {
            public String networkId() { return networkId; }
            public void finish() throws Exception {
                Path mysql = directory.resolve("dependency");
                if (!Files.notExists(mysql, LinkOption.NOFOLLOW_LINKS)) {
                    String credentialHash = "0".repeat(64);
                    if (!Files.notExists(mysql.resolve("intent.json"), LinkOption.NOFOLLOW_LINKS)) {
                        String profile = ContainerJournal.json(mysql.resolve("intent.json")).path("profile").asText();
                        String prefix = namespace + "-service-v1:" + networkId + ":";
                        if (!profile.startsWith(prefix) || !profile.substring(prefix.length()).matches("[a-f0-9]{64}")) throw new IOException("Invalid DB profile");
                        credentialHash = profile.substring(prefix.length());
                    }
                    try (var db = new WorkerContainerLifecycle(mysql, databaseSpec(runId, artifactSha256), new DependencyEngine(networkId, credentialHash))) {
                        if (db.reconcile() == WorkerContainerLifecycle.Recovery.REQUIRES_OPERATOR) throw new IOException("Database request unconfirmed");
                    }
                }
                sameEngine();
                String found = docker.run("network", "ls", "--no-trunc", "--quiet", "--filter", "id=" + networkId).trim();
                if (!found.isEmpty()) {
                    if (!found.equals(networkId) || removed) throw new IOException("Network identity changed");
                    var value = WireJson.parse(docker.run("network", "inspect", "--format", "{{json .}}", networkId).getBytes(StandardCharsets.UTF_8));
                    if (!networkId.equals(value.path("Id").asText()) || !intent.path("name").asText().equals(value.path("Name").asText())
                            || !value.path("Internal").asBoolean() || value.path("Attachable").asBoolean() || !"bridge".equals(value.path("Driver").asText())
                            || !token.equals(value.path("Labels").path(DockerContainerEngine.OWNER).asText())
                            || !runId.equals(value.path("Labels").path(DockerContainerEngine.RUN).asText())
                            || !(namespace + "-network").equals(value.path("Labels").path("engineering-lab.resource").asText())
                            || !value.path("Containers").isObject() || !value.path("Containers").isEmpty()) throw new IOException("Network ownership or endpoints changed");
                }
                if (!removing) ContainerJournal.write(directory.resolve("removing.json"), WireJson.object().put("intentSha256", intentHash));
                sameEngine();
                if (!found.isEmpty()) docker.run("network", "rm", networkId);
                sameEngine();
                if (!docker.run("network", "ls", "--no-trunc", "--quiet", "--filter", "id=" + networkId).isBlank()) throw new IOException("Network removal unconfirmed");
                sameEngine();
                if (!removed) ContainerJournal.write(directory.resolve("removed.json"), WireJson.object().put("intentSha256", intentHash));
            }
            private void sameEngine() throws Exception {
                if (!engine.equals(docker.run("info", "--format", "{{.ID}}").trim())) throw new IOException("Docker engine changed");
            }
        };
    }
    private WorkerContainerLifecycle.Spec databaseSpec(String runId, String artifactSha256) {
        return new WorkerContainerLifecycle.Spec(runId, artifactSha256, serviceImage,
                redis ? List.of("/bin/sh", "-c", "exec redis-server --save '' --appendonly no --maxmemory 32mb --maxmemory-policy noeviction") : List.of("/usr/local/bin/docker-entrypoint.sh", "mysqld", "--skip-log-bin", "--innodb-buffer-pool-size=64M", "--performance-schema=OFF"));
    }
    @Override public Session open(ObjectNode command, Path workspace, String artifactSha256) throws Exception {
        if (!(redis ? Set.of("career.equity-system.redis-lock") : Set.of("career.equity-system.deletion", "career.equity-system.registration")).contains(command.path("experimentId").asText())) throw new IllegalArgumentException("Wrong equity resource binding");
        if (!command.path("runId").asText().matches("[a-zA-Z0-9._-]{1,200}") || artifactSha256 == null || !artifactSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid resource identity");
        return new Resources(command, Files.createDirectory(workspace.resolve(namespace + "-resources")), artifactSha256);
    }
    private final class Resources implements Session {
        private final ObjectNode command;
        private final String artifactSha256;
        private final Path directory;
        private final String token = UUID.randomUUID().toString(), password = secret(), rootPassword = secret();
        private final String name = "engineering-lab-" + namespace + "-net-" + token;
        private String engineId, networkId, intentHash;
        private boolean attempted, finished;
        private WorkerContainerLifecycle database;
        Resources(ObjectNode command, Path directory, String artifactSha256) { this.command = command.deepCopy(); this.directory = directory; this.artifactSha256 = artifactSha256; }
        public void prepare() throws Exception {
            if (attempted || finished) throw new IOException("Resource session cannot be reused");
            engineId = docker.run("info", "--format", "{{.ID}}").trim();
            if (!engineId.matches("[a-zA-Z0-9:-]{1,200}")) throw new IOException("Invalid Docker engine identity");
            var intent = WireJson.object().put("version", 1).put("profile", (namespace + "-network-v1"))
                    .put("engineId", engineId).put("token", token).put("name", name).put("serviceImage", serviceImage)
                    .put("runId", command.path("runId").asText()).put("artifactSha256", artifactSha256);
            byte[] bytes = WireJson.encode(intent); write("intent", bytes); intentHash = hash(bytes); attempted = true;
            sameEngine();
            networkId = docker.run("network", "create", "--driver", "bridge", "--internal",
                    "--label", DockerContainerEngine.OWNER + "=" + token, "--label", DockerContainerEngine.RUN + "=" + command.path("runId").asText(),
                    "--label", "engineering-lab.resource=" + namespace + "-network", name).trim();
            if (!networkId.matches("[a-f0-9]{64}")) { networkId = null; throw new IOException("Network create unconfirmed"); }
            verifyNetwork(); write("created", receipt().put("id", networkId));
            var mysql = new DependencyEngine(networkId, password, rootPassword);
            var spec = databaseSpec(command.path("runId").asText(), artifactSha256);
            database = new WorkerContainerLifecycle(Files.createDirectory(directory.resolve("dependency")), spec, mysql);
            String id = database.create(); database.start();
            long deadline = Math.min(command.path("deadlineEpochMs").asLong(), System.currentTimeMillis() + 120000);
            while (true) {
                if (System.currentTimeMillis() >= deadline) throw new IOException("Equity dependency readiness deadline");
                try {
                    String result = redis ? docker.run("exec", id, "redis-cli", "ping") : docker.run("exec", "--env", "MYSQL_PWD=" + password, id,
                            "mysql", "--user=lab", "--database=equity_lab", "--batch", "--skip-column-names", "--execute=SELECT 1");
                    if (result.trim().equals(redis ? "PONG" : "1")) break;
                } catch (IOException ignored) { /* Read-only readiness retries; never repeat create/start. */ }
                Thread.sleep(200);
            }
            verifyNetwork();
        }
        public String networkId() { if (networkId == null) throw new IllegalStateException("Network not ready"); return networkId; }
        public Map<String, String> environment() {
            return redis ? Map.of("LAB_EQUITY_REDIS_HOST", "equity-db") : Map.of("LAB_EQUITY_DB_HOST", "equity-db", "LAB_EQUITY_DB_PASSWORD", password);
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
                docker.run("network", "rm", networkId);
                sameEngine();
                if (!docker.run("network", "ls", "--no-trunc", "--quiet", "--filter", "id=" + networkId).isBlank())
                    throw new IOException("Network removal unconfirmed");
                sameEngine(); write("removed", receipt());
            } finally { if (database != null) database.close(); }
        }
        private void sameEngine() throws Exception {
            if (!Objects.equals(engineId, docker.run("info", "--format", "{{.ID}}").trim())) throw new IOException("Docker engine changed");
        }
        private ObjectNode network() throws Exception {
            return WireJson.parse(docker.run("network", "inspect", "--format", "{{json .}}", networkId).getBytes(StandardCharsets.UTF_8));
        }
        private void verifyNetwork() throws Exception {
            sameEngine(); var value = network();
            if (!networkId.equals(value.path("Id").asText()) || !name.equals(value.path("Name").asText())
                    || !value.path("Internal").asBoolean() || value.path("Attachable").asBoolean()
                    || !"bridge".equals(value.path("Driver").asText())
                    || !(namespace + "-network").equals(value.path("Labels").path("engineering-lab.resource").asText())
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
    private final class DependencyEngine extends DockerContainerEngine {
        private final String network, password, rootPassword, credentialHash;
        DependencyEngine(String network, String password, String rootPassword) { this.network = network; this.password = password; this.rootPassword = rootPassword; this.credentialHash = hash((password + rootPassword).getBytes(StandardCharsets.UTF_8)); }
        DependencyEngine(String network, String credentialHash) { this.network = network; this.credentialHash = credentialHash; this.password = null; this.rootPassword = null; }
        @Override public String profile() { return namespace + "-service-v1:" + network + ":" + credentialHash; }
        @Override public String create(WorkerContainerLifecycle.Spec spec, WorkerContainerLifecycle.Owner owner) throws Exception {
            if (password == null) throw new IOException("Recovery engine cannot create");
            var args = new ArrayList<>(List.of("create", "--pull=never", "--name", owner.name(),
                    "--label", OWNER + "=" + owner.token(), "--label", RUN + "=" + spec.runId(), "--label", ARTIFACT + "=" + spec.artifactSha256(),
                    "--user=999:999", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--network", network, "--network-alias=equity-db",
                    "--ipc=private", "--restart=no", "--memory=512m", "--cpus=1", "--pids-limit=128", "--log-driver=none",
                    "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=16m", "--tmpfs", "/var/lib/mysql:rw,nosuid,nodev,noexec,size=256m,uid=999,gid=999",
                    "--tmpfs", "/var/run/mysqld:rw,nosuid,nodev,noexec,size=1m,uid=999,gid=999",
                    "--env", "MYSQL_DATABASE=equity_lab", "--env", "MYSQL_USER=lab", "--env", "MYSQL_PASSWORD=" + password,
                    "--env", "MYSQL_ROOT_PASSWORD=" + rootPassword, "--entrypoint", spec.command().getFirst(), spec.imageId()));
            if (redis) {
                // No image data volume, persistence, host ports or credentials on this isolated network.
                args = new ArrayList<>(List.of("create", "--pull=never", "--name", owner.name(),
                    "--label", OWNER + "=" + owner.token(), "--label", RUN + "=" + spec.runId(), "--label", ARTIFACT + "=" + spec.artifactSha256(),
                    "--user=65534:65534", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--network", network, "--network-alias=equity-db",
                    "--ipc=private", "--restart=no", "--memory=256m", "--cpus=1", "--pids-limit=64", "--log-driver=none",
                    "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=16m", "--tmpfs", "/data:rw,nosuid,nodev,noexec,size=1m,uid=65534,gid=65534",
                    "--entrypoint", spec.command().getFirst(), spec.imageId()));
            }
            args.addAll(spec.command().subList(1, spec.command().size())); return command(args.toArray(String[]::new)).trim();
        }
        @Override public boolean verifySnapshot(ObjectNode value, WorkerContainerLifecycle.Spec spec, WorkerContainerLifecycle.Owner owner, String id) {
            if (!networkMatches(value, network, "equity-db")) return false;
            if (redis) {
                var copy = value.deepCopy();
                var host = (ObjectNode)copy.path("HostConfig");
                if (!network.equals(host.path("NetworkMode").asText())) return false;
                var tmpfs = host.path("Tmpfs");
                if (tmpfs.size() != 2 || !"rw,nosuid,nodev,noexec,size=1m,uid=65534,gid=65534".equals(tmpfs.path("/data").asText())) return false;
                if (!copy.path("Mounts").isArray()) return false;
                for (var mount : copy.path("Mounts")) if (!"tmpfs".equals(mount.path("Type").asText())
                    || !Set.of("/tmp", "/data").contains(mount.path("Destination").asText())) return false;
                host.put("NetworkMode", "none"); ((ObjectNode)tmpfs).remove("/data"); copy.putArray("Mounts");
                return DockerContainerEngine.matches(copy, spec, owner, id);
            }

            // Normalize only the explicitly allowed DB profile differences, then reuse the strict worker checks.
            var copy = value.deepCopy(); var config = (ObjectNode)copy.path("Config"); var host = (ObjectNode)copy.path("HostConfig");
            if (!"999:999".equals(config.path("User").asText()) || !network.equals(host.path("NetworkMode").asText())
                    || host.path("Memory").asLong() != 512L * 1024 * 1024 || host.path("PidsLimit").asInt() != 128) return false;
            Map<String,String> env = new HashMap<>();
            for (var entry : config.path("Env")) {
                String[] pair = entry.asText().split("=", 2);
                if (pair.length != 2 || env.putIfAbsent(pair[0], pair[1]) != null) return false;
            }
            if (!"equity_lab".equals(env.get("MYSQL_DATABASE")) || !"lab".equals(env.get("MYSQL_USER"))
                    || !env.getOrDefault("MYSQL_PASSWORD", "").matches("[a-f0-9]{64}") || !env.getOrDefault("MYSQL_ROOT_PASSWORD", "").matches("[a-f0-9]{64}")
                    || !credentialHash.equals(hash((env.get("MYSQL_PASSWORD") + env.get("MYSQL_ROOT_PASSWORD")).getBytes(StandardCharsets.UTF_8)))) return false;
            var tmpfs = host.path("Tmpfs");
            if (tmpfs.size() != 3 || !"rw,nosuid,nodev,noexec,size=256m,uid=999,gid=999".equals(tmpfs.path("/var/lib/mysql").asText())
                    || !"rw,nosuid,nodev,noexec,size=1m,uid=999,gid=999".equals(tmpfs.path("/var/run/mysqld").asText())) return false;
            if (!copy.path("Mounts").isArray()) return false;
            for (var mount : copy.path("Mounts")) if (!"tmpfs".equals(mount.path("Type").asText())
                    || !Set.of("/tmp", "/var/lib/mysql", "/var/run/mysqld").contains(mount.path("Destination").asText())) return false;
            config.put("User", "65534:65534"); host.put("NetworkMode", "none").put("Memory", 256L * 1024 * 1024).put("PidsLimit", 64);
            ((ObjectNode)tmpfs).remove(List.of("/var/lib/mysql", "/var/run/mysqld")); copy.putArray("Mounts");
            return DockerContainerEngine.matches(copy, spec, owner, id);
        }
    }
}
