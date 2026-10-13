package com.portfolio.mcplab.worker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Administrator-owned workspace journal. Container cleanup never releases server admission. */
final class SequenceContainerRecovery implements AutoCloseable {
    static final String JOURNAL = "sequence-container.properties";
    static final String IMAGE = "mysql:8.0.39";
    private static final int MAX_BYTES = 4096;
    private final Path workspace;
    private final FileChannel channel;
    private final FileLock lock;
    record Intent(String runId, String artifactSha256, String daemonId, String token) {
        Intent {
            if (runId == null || !runId.matches("[a-zA-Z0-9._-]{1,200}")
                    || artifactSha256 == null || !artifactSha256.matches("[a-f0-9]{64}")
                    || daemonId == null || !daemonId.matches("[a-zA-Z0-9:._-]{1,200}")
                    || token == null || !token.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
                throw new IllegalArgumentException("RECOVERY_IDENTITY_INVALID");
        }
        String name() { return "engineering-lab-sequence-" + token; }
        Map<String, String> labels() {
            return Map.of("engineering-lab.run-id", runId, "engineering-lab.owner-token", token,
                    "engineering-lab.artifact-sha256", artifactSha256, "engineering-lab.resource", "sequence-mysql-v1");
        }
    }
    record Container(String id, String name, String image, Map<String, String> labels) {}
    interface Containers {
        String daemonId();
        List<String> candidates(String token);
        Container inspect(String id);
        void remove(String id);
    }

    static SequenceContainerRecovery lock(Path workspace) throws IOException {
        if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) throw new IOException("RECOVERY_WORKSPACE_INVALID");
        Path real = workspace.toRealPath();
        var channel = FileChannel.open(real.resolve("sequence-container.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            var lock = channel.tryLock();
            if (lock == null) throw new IOException("RECOVERY_WORKER_ACTIVE");
            return new SequenceContainerRecovery(real, channel, lock);
        } catch (IOException | RuntimeException e) { channel.close(); throw e; }
    }
    private SequenceContainerRecovery(Path workspace, FileChannel channel, FileLock lock) {
        this.workspace = workspace; this.channel = channel; this.lock = lock;
    }
    Intent create(String runId, String hash, String daemonId) throws IOException {
        var intent = new Intent(runId, hash, daemonId, UUID.randomUUID().toString());
        var properties = new Properties();
        properties.setProperty("version", "1"); properties.setProperty("runId", intent.runId());
        properties.setProperty("artifactSha256", intent.artifactSha256()); properties.setProperty("daemonId", intent.daemonId());
        properties.setProperty("token", intent.token());
        var buffer = new ByteArrayOutputStream(); properties.store(buffer, "Sequence container ownership intent; retain after recovery");
        byte[] bytes = buffer.toByteArray();
        if (bytes.length > MAX_BYTES) throw new IOException("RECOVERY_JOURNAL_LIMIT");
        try (var file = FileChannel.open(workspace.resolve(JOURNAL), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var content = ByteBuffer.wrap(bytes); while (content.hasRemaining()) file.write(content);
            file.force(true);
        }
        return intent;
    }
    Intent read(String expectedRun, String expectedHash) throws IOException {
        Path file = workspace.resolve(JOURNAL);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("RECOVERY_JOURNAL_INVALID");
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IOException("RECOVERY_JOURNAL_LIMIT");
        var p = new Properties();
        try {
            p.load(new ByteArrayInputStream(bytes));
            if (!p.stringPropertyNames().equals(Set.of("version", "runId", "artifactSha256", "daemonId", "token"))
                    || !"1".equals(p.getProperty("version"))) throw new IllegalArgumentException();
            var intent = new Intent(p.getProperty("runId"), p.getProperty("artifactSha256"), p.getProperty("daemonId"), p.getProperty("token"));
            if (!intent.runId().equals(expectedRun) || !intent.artifactSha256().equals(expectedHash)) throw new IllegalArgumentException();
            return intent;
        } catch (IllegalArgumentException e) { throw new IOException("RECOVERY_JOURNAL_INVALID", e); }
    }
    int recover(String expectedRun, String expectedHash, Containers containers) throws IOException {
        var intent = read(expectedRun, expectedHash);
        if (!intent.daemonId().equals(containers.daemonId())) throw new IOException("RECOVERY_DAEMON_MISMATCH");
        var candidates = containers.candidates(intent.token());
        if (candidates.size() > 1) throw new IOException("RECOVERY_RESOURCE_LIMIT");
        int removed = 0;
        if (!candidates.isEmpty()) {
            String id = candidates.getFirst();
            if (id == null || !id.matches("[a-f0-9]{64}")) throw new IOException("RECOVERY_CONTAINER_INVALID");
            var found = containers.inspect(id);
            if (found != null) {
                if (!id.equals(found.id()) || !("/" + intent.name()).equals(found.name()) || !IMAGE.equals(found.image())
                        || found.labels() == null || !found.labels().entrySet().containsAll(intent.labels().entrySet()))
                    throw new IOException("RECOVERY_OWNERSHIP_MISMATCH");
                containers.remove(id); removed++;
            }
        }
        if (!containers.candidates(intent.token()).isEmpty()) throw new IOException("RECOVERY_CLEANUP_UNCONFIRMED");
        if (!intent.daemonId().equals(containers.daemonId())) throw new IOException("RECOVERY_DAEMON_MISMATCH");
        return removed;
    }
    static Containers docker(DockerClient client) {
        return new Containers() {
            public String daemonId() { return client.infoCmd().exec().getId(); }
            public List<String> candidates(String token) {
                // One sequence DB is expected. Two matches are already ambiguous; never list the daemon globally.
                return client.listContainersCmd().withShowAll(true).withLimit(2)
                        .withLabelFilter(Map.of("engineering-lab.owner-token", token)).exec().stream().map(c -> c.getId()).toList();
            }
            public Container inspect(String id) {
                try {
                    var result = client.inspectContainerCmd(id).exec();
                    return new Container(result.getId(), result.getName(), result.getConfig().getImage(), result.getConfig().getLabels());
                } catch (NotFoundException e) { return null; }
            }
            public void remove(String id) {
                try { client.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec(); }
                catch (NotFoundException ignored) { /* Concurrent Ryuk removal is already clean. */ }
            }
        };
    }
    public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
}
