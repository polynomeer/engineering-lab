package com.pnomeer.lab.core.input;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;

/** Owned, bounded staging file. Submission owns it until execution/rejection finishes. */
public final class InputPayload implements AutoCloseable {
    public static final int MAX_BYTES = 50 * 1024 * 1024;
    public static final int MAX_STAGED = 16;
    private static final Semaphore SLOTS = new Semaphore(MAX_STAGED);
    private final Path path;
    private final byte[] bytes;
    private final long size;
    private final String sha256;
    private boolean closed;
    private InputPayload(Path path, byte[] bytes, long size, String sha256) {
        this.path = path; this.bytes = bytes; this.size = size; this.sha256 = sha256;
    }
    public static InputPayload memory(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Input exceeds 50 MiB");
        byte[] copy = bytes.clone();
        return new InputPayload(null, copy, copy.length, HexFormat.of().formatHex(digest().digest(copy)));
    }
    public static InputPayload stage(InputStream source, int limit) throws IOException {
        if (limit < 1 || limit > MAX_BYTES) throw new IllegalArgumentException("Invalid input limit");
        if (!SLOTS.tryAcquire()) { try (source) { throw new CapacityExceeded(); } }
        Path file = null;
        try (source) {
            file = Files.createTempFile("engineering-lab-upload-", ".xlsx");
            long size = 0; var digest = digest(); byte[] buffer = new byte[8192];
            try (var out = Files.newOutputStream(file)) {
                int count;
                while ((count = source.read(buffer, 0, (int)Math.min(buffer.length, limit - size + 1))) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Upload interrupted");
                    size += count;
                    if (size > limit) throw new LimitExceeded();
                    out.write(buffer, 0, count); digest.update(buffer, 0, count);
                }
            }
            return new InputPayload(file, null, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | RuntimeException | Error failure) {
            boolean deleted = true;
            if (file != null) try { Files.deleteIfExists(file); }
            catch (IOException ex) { deleted = false; failure.addSuppressed(ex); }
            if (deleted) SLOTS.release();
            throw failure;
        }
    }
    public synchronized InputStream openStream() throws IOException {
        if (closed) throw new IOException("Input payload is closed");
        return path == null ? new ByteArrayInputStream(bytes) : Files.newInputStream(path);
    }
    public long size() { return size; }
    public String sha256() { return sha256; }
    @Override public synchronized void close() {
        if (closed) return;
        if (path != null) {
            try { Files.deleteIfExists(path); }
            catch (IOException ex) { throw new UncheckedIOException("Cannot delete staged input", ex); }
            SLOTS.release();
        }
        closed = true;
    }
    public static final class LimitExceeded extends IOException {
        public LimitExceeded() { super("Upload exceeds input byte limit"); }
    }
    public static final class CapacityExceeded extends IllegalStateException {
        public CapacityExceeded() { super("Input staging capacity reached"); }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
