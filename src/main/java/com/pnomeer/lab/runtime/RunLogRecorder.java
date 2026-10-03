package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.storage.ArtifactReference;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Explicit run events, not a global logging appender. No free-form messages or retained event list. */
public final class RunLogRecorder {
    public enum Stage { EXECUTION, JVM, JFR, STORAGE }
    public enum Event { STARTED, SUCCEEDED, FAILED, INTERRUPTED, EVIDENCE_FINISHED }
    public enum Reason { NONE, EXECUTION_FAILED, JVM_FAILED, JFR_FAILED, STORAGE_FAILED, INTERRUPTED, OUT_OF_MEMORY }
    private static final int MAX_LINE_BYTES = 1024;
    @FunctionalInterface interface OutputFactory { OutputStream open(Path path) throws IOException; }
    private final OutputFactory outputFactory;
    private final Path root;
    private final RunLogProperties properties;
    private final JsonMapper json = JsonMapper.builder().build();

    public RunLogRecorder(Path root, RunLogProperties properties) {
        this(root, properties, path -> Files.newOutputStream(path));
    }
    RunLogRecorder(Path root, RunLogProperties properties, OutputFactory outputFactory) {
        this.root = root.toAbsolutePath().normalize(); this.properties = properties; this.outputFactory = outputFactory;
    }
    public Map<String, String> parameters() {
        return Map.of("observation.logs.mode", properties.enabled() ? "LIFECYCLE_V1" : "OFF",
                "observation.logs.maxEvents", Integer.toString(properties.maxEvents()),
                "observation.logs.maxBytes", Long.toString(properties.maxBytes()));
    }
    public Session start(String runId) throws IOException {
        if (!properties.enabled()) return null;
        if (runId == null || runId.length() > 100 || runId.isBlank()) throw new IllegalArgumentException("Invalid log run ID");
        long started = System.nanoTime();
        Path directory = root.resolve("logs");
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Log directory must not be a symbolic link");
        Path temporary = Files.createTempFile(directory, "capture-", ".partial");
        OutputStream output = null;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            output = new DigestOutputStream(outputFactory.open(temporary), digest);
            var session = new Session(runId, temporary, digest, output);
            session.captureNanos = System.nanoTime() - started;
            session.record(Stage.EXECUTION, Event.STARTED, Reason.NONE);
            return session;
        } catch (NoSuchAlgorithmException | IOException | RuntimeException | Error ex) {
            if (output != null) {
                try { output.close(); } catch (IOException closeFailure) { ex.addSuppressed(closeFailure); }
            }
            Files.deleteIfExists(temporary);
            if (ex instanceof Error error) throw error;
            throw new IOException("Cannot start run log", ex);
        }
    }

    public final class Session implements AutoCloseable {
        private final String runId;
        private final Path temporary;
        private final MessageDigest digest;
        private final OutputStream output;
        private long events, bytes, dropped, captureNanos;
        private IOException failure;
        private boolean closed;
        private Session(String runId, Path temporary, MessageDigest digest, OutputStream output) {
            this.runId = runId; this.temporary = temporary; this.digest = digest; this.output = output;
        }
        /** Latch write failures so cleanup and metric drain still run; finish propagates them. */
        public synchronized void record(Stage stage, Event event, Reason reason) {
            if (closed) throw new IllegalStateException("Run log is closed");
            if (failure != null) return;
            long started = System.nanoTime();
            try {
                byte[] line = line(stage, event, reason);
                if (events >= properties.maxEvents() - 1 || bytes + line.length > properties.maxBytes() - MAX_LINE_BYTES) {
                    dropped++; return;
                }
                write(line);
            } catch (IOException | RuntimeException ex) { failure = new IOException("Run log write failed", ex); }
            finally { captureNanos += System.nanoTime() - started; }
        }
        private byte[] line(Stage stage, Event event, Reason reason) throws IOException {
            byte[] line = (json.writeValueAsString(Map.of("schemaVersion", 1, "timestampEpochMs", System.currentTimeMillis(),
                    "job_id", runId, "stage", stage.name(), "event", event.name(), "reason", reason.name(),
                    "droppedEvents", dropped)) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (line.length > MAX_LINE_BYTES) throw new IOException("Run log event exceeds fixed shape limit");
            return line;
        }
        private void write(byte[] line) throws IOException {
            if (bytes + line.length > properties.maxBytes()) throw new IOException("Run log exceeds byte limit");
            output.write(line); bytes += line.length; events++;
        }
        /** This end event precedes log registration and durable Run completion. */
        public synchronized ArtifactReference finish(Reason reason) throws IOException {
            if (closed) throw new IllegalStateException("Run log is closed");
            long started = System.nanoTime();
            try {
                if (failure != null) throw failure;
                write(line(Stage.STORAGE, Event.EVIDENCE_FINISHED, reason));
                output.close();
                String id = "log_" + UUID.randomUUID();
                var reference = new ArtifactReference(id, runId, "logs/" + id + ".ndjson",
                        "application/x-ndjson", bytes, HexFormat.of().formatHex(digest.digest()));
                Files.move(temporary, temporary.resolveSibling(id + ".ndjson"), StandardCopyOption.ATOMIC_MOVE);
                return reference;
            } finally {
                try { close(); }
                finally { captureNanos += System.nanoTime() - started; }
            }
        }
        public synchronized Map<String, Long> counters() {
            return Map.of("observation.logs.events", events, "observation.logs.dropped", dropped,
                    "observation.logs.bytes", bytes, "observation.logs.captureNanos", captureNanos);
        }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            try { output.close(); }
            finally { Files.deleteIfExists(temporary); }
        }
    }
}
