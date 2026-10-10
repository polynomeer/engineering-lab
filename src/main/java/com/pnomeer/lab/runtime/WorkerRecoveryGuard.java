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

    // Unknown accessibility and symlinks block admission too. Never infer cleanup from a PID.
    boolean pending() { return !Files.notExists(marker, LinkOption.NOFOLLOW_LINKS); }

    Ticket begin(String runId, String artifactSha256) throws IOException {
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

    final class Ticket {
        private final byte[] content;
        private Ticket(byte[] content) { this.content = content; }
        void confirmSuccess() throws IOException {
            byte[] current;
            try (var input = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
                current = input.readNBytes(MAX_BYTES + 1);
            }
            if (!Arrays.equals(current, content)) throw new IOException("Recovery marker ownership changed");
            // The parent directory is administrator-owned; operators must not mutate it during execution.
            Files.delete(marker);
        }
    }
}
