package com.pnomeer.lab.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.pnomeer.lab.core.storage.ArtifactReference;
import com.pnomeer.lab.worker.ProtocolException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

/** Imports verified report bytes into administrator-owned storage; never serves worker workspace paths. */
final class WorkerArtifactImporter {
    record Limits(int files, long fileBytes, long totalBytes) {
        static final Limits DEFAULT = new Limits(32, 16L * 1024 * 1024, 64L * 1024 * 1024);
        Limits {
            if (files < 1 || files > 32 || fileBytes < 1 || fileBytes > 16L * 1024 * 1024
                    || totalBytes < 1 || totalBytes > 64L * 1024 * 1024) throw new IllegalArgumentException("Invalid artifact limits");
        }
    }
    private final Path storageRoot;
    private final Limits limits;
    WorkerArtifactImporter(Path storageRoot, Limits limits) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize(); this.limits = Objects.requireNonNull(limits);
    }
    Session open(String runId, Consumer<ArtifactReference> register) { return new Session(runId, register); }
    final class Session {
        private final String runId;
        private final Consumer<ArtifactReference> register;
        private final Set<String> paths = new HashSet<>();
        private long total;
        private boolean failed;
        Session(String runId, Consumer<ArtifactReference> register) { this.runId = runId; this.register = register; }
        void accept(Path workspace, JsonNode payload) throws IOException {
            if (failed) throw new ProtocolException("ARTIFACT_SESSION_FAILED");
            try { importReport(workspace, payload); }
            catch (IOException | RuntimeException e) { failed = true; throw e; }
        }
        private void importReport(Path workspace, JsonNode payload) throws IOException {
            if (!"REPORT".equals(payload.path("kind").textValue())) throw new ProtocolException("ARTIFACT_KIND_UNAVAILABLE");
            String relative = payload.path("path").textValue(), hash = payload.path("sha256").textValue();
            if (relative == null || relative.length() > 1024 || relative.startsWith("/") || relative.contains("\\")
                    || relative.contains(":") || relative.indexOf('\0') >= 0
                    || Arrays.stream(relative.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
                throw new ProtocolException("ARTIFACT_PATH");
            if (hash == null || !hash.matches("[a-f0-9]{64}") || !payload.path("sizeBytes").isIntegralNumber()
                    || !payload.path("sizeBytes").canConvertToLong()) throw new ProtocolException("ARTIFACT_METADATA");
            long expected = payload.path("sizeBytes").longValue();
            if (expected < 0 || expected > limits.fileBytes() || expected > limits.totalBytes() - total || paths.size() >= limits.files())
                throw new ProtocolException("ARTIFACT_LIMIT");
            if (paths.contains(relative)) throw new ProtocolException("ARTIFACT_DUPLICATE");
            Files.createDirectories(storageRoot);
            Path destinationDirectory = storageRoot.resolve("worker-reports");
            try { Files.createDirectory(destinationDirectory); } catch (FileAlreadyExistsException ignored) {}
            if (!Files.isDirectory(destinationDirectory, LinkOption.NOFOLLOW_LINKS)) throw new ProtocolException("ARTIFACT_STORAGE_PATH");
            // The storage root is owned by the controller, not supplied to the worker as an output directory.
            Path temporary = Files.createTempFile(destinationDirectory, ".import-", ".tmp");
            Path destination = null;
            try {
                MessageDigest digest = digest(); long copied;
                try (Source source = openSource(workspace, relative);
                     OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.WRITE)) {
                    if (source.size() != expected) throw new ProtocolException("ARTIFACT_SIZE_MISMATCH");
                    ByteBuffer buffer = ByteBuffer.allocate(8192); copied = 0;
                    while (source.channel().read(buffer) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Artifact import interrupted");
                        int count = buffer.position();
                        if (count > expected - copied) throw new ProtocolException("ARTIFACT_SIZE_MISMATCH");
                        digest.update(buffer.array(), 0, count); output.write(buffer.array(), 0, count);
                        copied += count; buffer.clear();
                    }
                }
                if (copied != expected) throw new ProtocolException("ARTIFACT_SIZE_MISMATCH");
                if (!HexFormat.of().formatHex(digest.digest()).equals(hash)) throw new ProtocolException("ARTIFACT_HASH_MISMATCH");
                String id = "worker_" + UUID.randomUUID();
                destination = destinationDirectory.resolve(id + ".bin");
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                register.accept(new ArtifactReference(id, runId, "worker-reports/" + destination.getFileName(),
                        "application/octet-stream", copied, hash));
                paths.add(relative); total += copied;
                destination = null; // Registration owns the verified copy, including after later Run failure.
            } finally {
                Files.deleteIfExists(temporary);
                if (destination != null) Files.deleteIfExists(destination);
            }
        }
    }
    private record Source(SeekableByteChannel channel, long size) implements AutoCloseable {
        public void close() throws IOException { channel.close(); }
    }
    private static Source openSource(Path workspace, String relative) throws IOException {
        // Pin each directory handle and prohibit symlinks, including a replaced workspace itself.
        Path parent = workspace.toAbsolutePath().normalize().getParent();
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(parent)) {
            if (!(directory instanceof SecureDirectoryStream<Path> secure)) throw new ProtocolException("SECURE_DIRECTORY_UNAVAILABLE");
            try (SecureDirectoryStream<Path> root = secure.newDirectoryStream(workspace.getFileName(), LinkOption.NOFOLLOW_LINKS)) {
                return descend(root, Path.of(relative), 0);
            }
        }
    }
    private static Source descend(SecureDirectoryStream<Path> directory, Path relative, int index) throws IOException {
        Path part = relative.getName(index);
        if (index < relative.getNameCount() - 1) {
            try (var child = directory.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS)) { return descend(child, relative, index + 1); }
        }
        var attributes = directory.getFileAttributeView(part, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (!attributes.isRegularFile()) throw new ProtocolException("ARTIFACT_NOT_REGULAR");
        var channel = directory.newByteChannel(part, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        try { return new Source(channel, channel.size()); }
        catch (IOException | RuntimeException e) { channel.close(); throw e; }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
