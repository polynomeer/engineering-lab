package com.pnomeer.lab.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.pnomeer.lab.worker.ProtocolException;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Copies a single bounded regular report, never extracts an arbitrary worker archive. */
final class ContainerArtifactTransfer {
    private final Set<String> paths = new HashSet<>();
    private long total;
    private Process active;
    private boolean closed;
    void copy(String id, Path workspace, JsonNode payload) throws Exception {
        String relative = payload.path("path").asText(); long size = payload.path("sizeBytes").asLong(-1);
        if (!"REPORT".equals(payload.path("kind").asText()) || !relative.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}"))
            throw new ProtocolException("CONTAINER_REPORT_PATH_OR_KIND");
        if (size < 0 || size > 16L * 1024 * 1024 || size > 64L * 1024 * 1024 - total || paths.size() >= 32 || !paths.add(relative))
            throw new ProtocolException("CONTAINER_REPORT_LIMIT");
        Process child;
        synchronized (this) {
            if (closed) throw new InterruptedIOException("Report transfer closed");
            active = new ProcessBuilder("docker", "exec", id, "tar", "-C", "/tmp/worker", "-cf", "-", "--", relative)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start(); child = active;
        }
        Path target = workspace.resolve(relative); boolean complete = false, created = false;
        try {
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW);
                 var raw = new Bounded(child.getInputStream(), size + 65536)) {
                created = true;
                extract(raw, output, relative, size);
            }
            if (!child.waitFor(2, TimeUnit.SECONDS) || child.exitValue() != 0) throw new ProtocolException("CONTAINER_REPORT_COPY_FAILED");
            total += size; complete = true;
        } finally {
            child.destroyForcibly();
            boolean interrupted = Thread.interrupted();
            boolean stopped = false;
            try { stopped = child.waitFor(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { interrupted = true; }
            synchronized (this) { if (stopped && active == child) active = null; }
            if (created && !complete) Files.deleteIfExists(target);
            if (interrupted) Thread.currentThread().interrupt();
            if (!stopped) throw new ProtocolException("CONTAINER_REPORT_COPY_CLEANUP_UNCONFIRMED");
        }
    }
    static void extract(InputStream raw, OutputStream output, String relative, long size) throws IOException {
        // Never use archive pathnames for filesystem writes; only the validated requested name.
        var input = new TarArchiveInputStream(raw);
        var entry = input.getNextEntry();
        if (entry == null || !entry.isFile() || entry.isSymbolicLink() || entry.isLink() || entry.isSparse()
                || !relative.equals(entry.getName()) || entry.getSize() != size)
            throw new ProtocolException("CONTAINER_REPORT_ENTRY");
        byte[] buffer = new byte[8192]; long copied = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Report transfer interrupted");
            if ((copied += count) > size) throw new ProtocolException("CONTAINER_REPORT_SIZE");
            output.write(buffer, 0, count);
        }
        if (copied != size || input.getNextEntry() != null) throw new ProtocolException("CONTAINER_REPORT_SIZE");
        // Consume the bounded physical stream, not the tar reader's logical EOF.
        while ((count = raw.read(buffer)) != -1) {
            for (int i = 0; i < count; i++) if (buffer[i] != 0) throw new ProtocolException("CONTAINER_REPORT_TRAILER");
        }
    }
    synchronized boolean stopped() { return active == null || !active.isAlive(); }
    synchronized void stop() {
        closed = true; if (active != null) active.destroyForcibly();
    }
    private static final class Bounded extends FilterInputStream {
        private long remaining;
        Bounded(InputStream input, long maximum) { super(input); remaining = maximum; }
        @Override public int read() throws IOException {
            if (remaining <= 0) throw new ProtocolException("CONTAINER_REPORT_ARCHIVE_LIMIT");
            int value = in.read(); if (value >= 0) remaining--; return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) throw new ProtocolException("CONTAINER_REPORT_ARCHIVE_LIMIT");
            int count = in.read(bytes, offset, (int)Math.min(length, remaining)); if (count > 0) remaining -= count; return count;
        }
        @Override public long skip(long count) throws IOException {
            byte[] block = new byte[8192]; long skipped = 0;
            while (skipped < count) { int n = read(block, 0, (int)Math.min(block.length, count - skipped)); if (n < 0) break; skipped += n; }
            return skipped;
        }
    }
}
