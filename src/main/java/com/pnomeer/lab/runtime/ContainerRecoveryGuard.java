package com.pnomeer.lab.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.worker.WireJson;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Version 2 admission is bound to the workspace before any Docker mutation. Never reads a v1 receipt. */
final class ContainerRecoveryGuard {
    private final Path root, marker, lock;
    private final String id;
    ContainerRecoveryGuard(Path root, String id) {
        this.root = root; this.id = id;
        marker = marker(root, id); lock = root.resolve(".container-" + id + ".lock");
    }
    static Path marker(Path root, String id) { return root.resolve(".container-active-" + id + ".json"); }
    boolean pending() { return !Files.notExists(marker, LinkOption.NOFOLLOW_LINKS); }
    Ticket acquire() throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid workspace root");
        if (!Files.notExists(lock, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid registration lock");
        var file = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            var acquired = file.tryLock();
            if (acquired == null) throw new IOException("Registration already owned");
            return new Ticket(file, acquired);
        } catch (Exception e) { file.close(); throw new IOException("Registration already owned", e); }
    }
    final class Ticket implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock ownership;
        private byte[] content;
        private ObjectNode value;
        Ticket(FileChannel channel, FileLock ownership) { this.channel = channel; this.ownership = ownership; }
        Path begin(ObjectNode command, ContainerWorkerManifest manifest, String profile) throws IOException {
            if (pending() || new WorkerRecoveryGuard(LocalJvmRegistration.marker(root, id)).pending()) throw new IOException("Recovery required");
            String workspace = "worker-" + UUID.randomUUID();
            value = WireJson.object().put("version", 2).put("registrationId", id)
                    .put("runId", command.path("runId").asText()).put("experimentId", command.path("experimentId").asText())
                    .put("artifactSha256", manifest.artifactSha256()).put("imageId", manifest.imageId()).put("moduleId", manifest.moduleId())
                    .put("resourceProfile", profile).put("workspace", workspace);
            ContainerJournal.write(marker, value); content = ContainerJournal.read(marker);
            return Files.createDirectory(root.resolve(workspace));
        }
        ObjectNode load(ContainerWorkerManifest manifest, String profile) throws IOException {
            content = ContainerJournal.read(marker); value = WireJson.parse(content);
            if (value.size() != 9 || !value.path("version").isIntegralNumber() || value.path("version").asInt() != 2
                    || !id.equals(value.path("registrationId").asText()) || !manifest.imageId().equals(value.path("imageId").asText())
                    || !manifest.artifactSha256().equals(value.path("artifactSha256").asText()) || !manifest.moduleId().equals(value.path("moduleId").asText())
                    || !profile.equals(value.path("resourceProfile").asText()) || !manifest.experiments().contains(value.path("experimentId").asText())
                    || !value.path("runId").asText().matches("[a-zA-Z0-9._-]{1,200}")
                    || !value.path("workspace").asText().matches("worker-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
                throw new IOException("Invalid container admission record");
            return value.deepCopy();
        }
        Path workspace() { return root.resolve(value.path("workspace").asText()); }
        void release() throws IOException {
            if (!ownership.isValid() || content == null || !Arrays.equals(content, ContainerJournal.read(marker))) throw new IOException("Container admission ownership changed");
            Files.delete(marker);
        }
        public void close() throws IOException { try { ownership.release(); } finally { channel.close(); } }
    }
}
