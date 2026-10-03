package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.storage.ArtifactReference;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.consumer.RecordingFile;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;

/** Opt-in process observation. Final artifacts are streamed, size checked and validated before registration. */
public final class JfrRecorder {
    static final Set<String> EVENTS = Set.of("jdk.CPULoad", "jdk.GarbageCollection", "jdk.GCHeapSummary");
    private final Path root;
    private final JfrProperties properties;

    public JfrRecorder(Path storageRoot, JfrProperties properties) {
        this.root = storageRoot.toAbsolutePath().normalize();
        this.properties = properties;
    }

    public Map<String, String> parameters() {
        return Map.of("observation.jfr.mode", properties.enabled() ? "BASIC_V1" : "OFF",
                "observation.jfr.durationSeconds", Integer.toString(properties.durationSeconds()),
                "observation.jfr.maxBytes", Long.toString(properties.maxBytes()),
                "observation.jfr.scope", "process");
    }

    public Session start(String runId) throws IOException {
        if (!properties.enabled()) return null;
        long started = System.nanoTime();
        // Application-owned subdirectory; never use user-supplied run IDs as file paths.
        Files.createDirectories(root);
        Path directory = root.resolve("jfr");
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("JFR directory must not be a symbolic link");
        var recording = new Recording();
        try {
            recording.setName("engineering-lab-basic-v1");
            recording.setToDisk(true);
            recording.setDumpOnExit(false);
            recording.setMaxSize(properties.maxBytes());
            recording.setDuration(Duration.ofSeconds(properties.durationSeconds()));
            EVENTS.forEach(name -> recording.enable(name).withoutStackTrace());
            recording.enable("jdk.CPULoad").withPeriod(Duration.ofSeconds(1));
            recording.start();
            return new Session(recording, directory, runId, System.nanoTime() - started);
        } catch (RuntimeException | Error ex) {
            recording.close();
            throw ex;
        }
    }

    public final class Session implements AutoCloseable {
        private final Recording recording;
        private final Path directory;
        private final String runId;
        private long lifecycleNanos;
        private boolean finished, durationReached;
        private long bytes;

        private Session(Recording recording, Path directory, String runId, long lifecycleNanos) {
            this.recording = recording; this.directory = directory; this.runId = runId; this.lifecycleNanos = lifecycleNanos;
        }

        /** The caller registers the returned metadata only after this method succeeds. */
        public ArtifactReference finish() throws IOException {
            if (finished) throw new IllegalStateException("JFR session already finished");
            finished = true;
            long started = System.nanoTime();
            Path temporary = null;
            try {
                durationReached = recording.getState() == RecordingState.STOPPED;
                if (recording.getState() == RecordingState.RUNNING) {
                    try { recording.stop(); }
                    catch (IllegalStateException ex) {
                        if (recording.getState() != RecordingState.STOPPED) throw ex;
                        durationReached = true; // The duration timer won the race.
                    }
                }
                temporary = Files.createTempFile(directory, "capture-", ".partial");
                MessageDigest digest;
                try { digest = MessageDigest.getInstance("SHA-256"); }
                catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
                long copied;
                try (var input = recording.getStream(null, null);
                     var output = new DigestOutputStream(Files.newOutputStream(temporary), digest)) {
                    if (input == null) throw new IOException("JFR recording has no data stream");
                    copied = copyBounded(input, output, properties.maxBytes());
                }
                // Other JFR recordings share JVM settings. Reject unexpected events or stacks.
                try (var file = new RecordingFile(temporary)) {
                    while (file.hasMoreEvents()) {
                        var event = file.readEvent();
                        if (!EVENTS.contains(event.getEventType().getName()) || event.getStackTrace() != null) {
                            throw new IOException("JFR contains events outside the basic profile");
                        }
                    }
                }
                String id = "jfr_" + UUID.randomUUID();
                Path destination = directory.resolve(id + ".jfr");
                var reference = new ArtifactReference(id, runId, "jfr/" + destination.getFileName(),
                        "application/octet-stream", copied, HexFormat.of().formatHex(digest.digest()));
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                temporary = null;
                bytes = copied;
                return reference;
            } finally {
                try { recording.close(); }
                finally {
                    lifecycleNanos += System.nanoTime() - started;
                    if (temporary != null) Files.deleteIfExists(temporary);
                }
            }
        }

        public Map<String, Long> counters() {
            return Map.of("observation.jfr.bytes", bytes, "observation.jfr.lifecycleNanos", lifecycleNanos,
                    "observation.jfr.durationLimitReached", durationReached ? 1L : 0L);
        }
        @Override public void close() { recording.close(); }
    }

    static long copyBounded(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > limit - total) throw new IOException("JFR artifact exceeds max-bytes");
            output.write(buffer, 0, count);
            total += count;
        }
        return total;
    }
}
