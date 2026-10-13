package com.pnomeer.lab.runtime;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Persistent fail-closed admission marker in an administrator-owned workspace directory. */
final class WorkerRecoveryGuard {
    private static final int MAX_BYTES = 4096;
    private final Path marker;
    WorkerRecoveryGuard(Path marker) { this.marker = marker; }
    private Path receipt() { return marker.resolveSibling(marker.getFileName() + ".completed"); }

    // Unknown accessibility and symlinks block admission too. Never infer cleanup from a PID.
    boolean pending() { return !Files.notExists(marker, LinkOption.NOFOLLOW_LINKS) || !Files.notExists(receipt(), LinkOption.NOFOLLOW_LINKS); }

    Ticket begin(String runId, String artifactSha256) throws IOException {
        if (!Files.notExists(receipt(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unreconciled completion receipt");
        if (runId == null || !runId.matches("[a-zA-Z0-9._-]{1,200}"))
            throw new IOException("Invalid recovery Run ID");
        var properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("runId", runId);
        properties.setProperty("artifactSha256", artifactSha256);
        properties.setProperty("startedAtEpochMs", Long.toString(System.currentTimeMillis()));
        properties.setProperty("token", UUID.randomUUID().toString());
        var bytes = new ByteArrayOutputStream();
        properties.store(bytes, "Worker active or recovery required; stop the controller before manual reconciliation");
        byte[] content = bytes.toByteArray();
        if (content.length > MAX_BYTES) throw new IOException("Recovery marker exceeds limit");
        // A crash during this write leaves even an incomplete marker blocking subsequent runs.
        try (var file = FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) file.write(buffer);
            file.force(true);
        }
        return new Ticket(content);
    }

    record Pending(Ticket ticket, String runId, Path workspace, String status, String reason) {}

    Pending completed(String artifactSha256) throws IOException {
        byte[] content = read(marker);
        var active = properties(content);
        if (!active.stringPropertyNames().equals(Set.of("version", "runId", "artifactSha256", "startedAtEpochMs", "token"))
                || !"1".equals(active.getProperty("version"))
                || !artifactSha256.equals(active.getProperty("artifactSha256"))
                || !active.getProperty("runId", "").matches("[a-zA-Z0-9._-]{1,200}"))
            throw new IOException("Invalid recovery marker");
        var done = properties(read(receipt()));
        if (!done.stringPropertyNames().equals(Set.of("version", "markerSha256", "workspace", "status", "reason"))
                || !"1".equals(done.getProperty("version")) || !hash(content).equals(done.getProperty("markerSha256"))
                || !done.getProperty("workspace", "").matches("worker-[a-zA-Z0-9_-]{1,100}"))
            throw new IOException("Invalid completion receipt");
        Path workspace = marker.getParent().resolve(done.getProperty("workspace"));
        if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid completed workspace");
        return new Pending(new Ticket(content), active.getProperty("runId"), workspace,
                done.getProperty("status"), done.getProperty("reason"));
    }

    private static byte[] read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid recovery file");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Recovery file exceeds limit");
            return bytes;
        }
    }
    private static Properties properties(byte[] bytes) throws IOException {
        var value = new Properties();
        value.load(new ByteArrayInputStream(bytes));
        return value;
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    final class Ticket {
        private final byte[] content;
        private Ticket(byte[] content) { this.content = content; }
        void recordCompletion(Path workspace, String status, String reason) throws IOException {
            if (!workspace.getParent().toRealPath().equals(marker.getParent().toRealPath())
                    || !workspace.getFileName().toString().matches("worker-[a-zA-Z0-9_-]{1,100}")
                    || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid completed workspace");
            requireOwner();
            var value = new Properties();
            value.setProperty("version", "1"); value.setProperty("markerSha256", hash(content));
            value.setProperty("workspace", workspace.getFileName().toString());
            value.setProperty("status", status); value.setProperty("reason", reason);
            var bytes = new ByteArrayOutputStream(); value.store(bytes, "Controller-confirmed transport completion");
            if (bytes.size() > MAX_BYTES) throw new IOException("Completion receipt exceeds limit");
            try (var file = FileChannel.open(receipt(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes.toByteArray());
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
        }
        void confirmSuccess() throws IOException {
            requireOwner();
            if (!Files.notExists(receipt(), LinkOption.NOFOLLOW_LINKS)) {
                if (!hash(content).equals(properties(read(receipt())).getProperty("markerSha256")))
                    throw new IOException("Completion receipt ownership changed");
                // Delete receipt first: a crash between deletes must retain the admission block.
                Files.delete(receipt());
            }
            // The parent directory is administrator-owned; operators must not mutate it during execution.
            Files.delete(marker);
        }
        private void requireOwner() throws IOException {
            if (!Arrays.equals(read(marker), content)) throw new IOException("Recovery marker ownership changed");
        }
    }
}
