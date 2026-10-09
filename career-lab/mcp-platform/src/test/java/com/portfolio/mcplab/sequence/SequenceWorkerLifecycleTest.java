package com.portfolio.mcplab.sequence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.worker.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Fault injection uses the actual deployment jar and run-owned Docker labels. */
class SequenceWorkerLifecycleTest {
    @TempDir Path workspace;

    @Test void parentEofDuringDatabasePreparationStopsRunAndRemovesMysql() throws Exception {
        try (var worker = launch(300000, Map.of())) {
            awaitContainer(worker);
            worker.process.getOutputStream().close();
            worker.assertInterruptedOrForced("PARENT_CONNECTION_LOST");
            worker.assertResourcesRemoved();
        }
    }

    @Test void deadlineDuringPreparationCannotProduceSuccess() throws Exception {
        try (var worker = launch(5000, Map.of())) {
            worker.assertInterruptedOrForced("DEADLINE_EXCEEDED");
            worker.assertResourcesRemoved();
        }
    }

    @Test void killedWorkerHasIncompleteEvidenceAndRyukRemovesOnlyItsResources() throws Exception {
        // Another Testcontainers session must survive the worker's reaper cleanup.
        try (var sentinel = new GenericContainer<>("mysql:8.0.39").withCommand("sleep", "300")) {
            sentinel.start();
            try (var worker = launch(300000, Map.of())) {
                awaitContainer(worker);
                worker.process.destroyForcibly();
                assertTrue(worker.process.waitFor(10, TimeUnit.SECONDS));
                assertNotEquals(0, worker.process.exitValue());
                var failure = assertThrows(ExecutionException.class, () -> worker.events.get(10, TimeUnit.SECONDS));
                assertInstanceOf(ProtocolException.class, failure.getCause());
                assertEquals("MISSING_RESULT", failure.getCause().getMessage());
                assertTrue(worker.observed.stream().noneMatch(e -> e.type().equals("RESULT")));
                worker.assertResourcesRemoved();
                assertTrue(sentinel.isRunning(), "An unrelated session's container must survive");
            }
        }
    }

    @Test void reportWriteFailureKeepsChecksButCannotCompleteSuccessfully() throws Exception {
        Files.writeString(workspace.resolve("sequence-report.json"), "existing report");
        try (var worker = launch(300000, Map.of())) {
            var events = worker.events.get(300, TimeUnit.SECONDS);
            var result = events.getLast();
            assertEquals("RESULT", result.type());
            assertEquals("FAILED", result.payload().path("status").asText());
            assertEquals(2, events.stream().filter(e -> e.type().equals("CHECK")).count());
            assertTrue(events.stream().noneMatch(e -> e.type().equals("ARTIFACT")));
            assertEquals("existing report", Files.readString(workspace.resolve("sequence-report.json")));
            worker.assertResourcesRemoved();
        }
    }

    @Test void disabledResourceReaperRejectsBeforeCreatingDatabase() throws Exception {
        try (var worker = launch(300000, Map.of("TESTCONTAINERS_RYUK_DISABLED", "true"))) {
            var events = worker.events.get(30, TimeUnit.SECONDS);
            assertEquals("FAILED", events.getLast().payload().path("status").asText());
            assertTrue(events.stream().noneMatch(e -> e.type().equals("CHECK")));
            assertTrue(worker.diagnostics.get(5, TimeUnit.SECONDS).contains("RESOURCE_REAPER_DISABLED"));
            assertTrue(containers(worker.runId).isEmpty());
        }
    }

    private Worker launch(long deadlineMs, Map<String, String> environment) throws Exception {
        Path jar = Path.of(System.getProperty("sequence.worker.jar"));
        String hash;
        try (var input = Files.newInputStream(jar)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[8192]; int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
            hash = HexFormat.of().formatHex(digest.digest());
        }
        String runId = UUID.randomUUID().toString();
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-jar", jar.toAbsolutePath().toString()).directory(workspace.toFile());
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"))
            builder.environment().remove(key);
        builder.environment().putAll(environment);
        var worker = new Worker(builder.start(), runId, hash);
        try {
            var command = WireJson.object().put("protocolVersion", 1).put("runId", runId)
                    .put("experimentId", "career.mcp-platform.sequence").put("definitionSha256", "a".repeat(64))
                    .put("deadlineEpochMs", System.currentTimeMillis() + deadlineMs).put("seed", 42)
                    .put("workspace", workspace.toAbsolutePath().toString());
            command.set("parameters", WireJson.object().put("variant", "ATOMIC_UPDATE").put("workers", 10).put("blockSize", 1000));
            WorkerProtocol.writeCommand(worker.process.getOutputStream(), command, ProtocolLimits.DEFAULT);
            return worker;
        } catch (Exception e) { worker.close(); throw e; }
    }

    private static List<com.github.dockerjava.api.model.Container> containers(String runId) {
        return DockerClientFactory.instance().client().listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of("engineering-lab.run-id", runId)).exec();
    }

    private static void awaitContainer(Worker worker) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(240).toNanos();
        while (System.nanoTime() < deadline && worker.process.isAlive()) {
            if (containers(worker.runId).stream().anyMatch(c -> "running".equals(c.getState()))) return;
            Thread.sleep(100);
        }
        fail("Worker did not create a running MySQL container");
    }

    private static final class Worker implements AutoCloseable {
        final Process process;
        final String runId;
        final List<WorkerProtocol.Event> observed = new CopyOnWriteArrayList<>();
        final ExecutorService io = Executors.newFixedThreadPool(2);
        final Future<List<WorkerProtocol.Event>> events;
        final Future<String> diagnostics;

        Worker(Process process, String runId, String hash) {
            this.process = process; this.runId = runId;
            diagnostics = io.submit(() -> {
                var kept = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int count;
                try (var input = process.getErrorStream()) {
                    while ((count = input.read(buffer)) != -1)
                        kept.write(buffer, 0, Math.min(count, 65536 - kept.size()));
                }
                return kept.toString(java.nio.charset.StandardCharsets.UTF_8);
            });
            events = io.submit(() -> {
                var reader = new WorkerProtocol.Reader(process.getInputStream(), new WorkerProtocol.Identity(
                        runId, "mcp-platform", "career.mcp-platform.sequence", "a".repeat(64), hash), ProtocolLimits.DEFAULT);
                WorkerProtocol.Event event;
                while ((event = reader.next()) != null) {
                    assertTrue(observed.size() < 16);
                    observed.add(event);
                }
                reader.verifyExit(process.waitFor());
                return List.copyOf(observed);
            });
        }

        void assertInterruptedOrForced(String reason) throws Exception {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Cancellation must terminate within its cleanup budget");
            if (process.exitValue() == 0) {
                var result = events.get(5, TimeUnit.SECONDS).getLast();
                assertEquals("RESULT", result.type());
                assertEquals("INTERRUPTED", result.payload().path("status").asText());
                assertEquals(reason, result.payload().path("reason").asText());
            } else {
                assertEquals(20, process.exitValue(), diagnostics.get(5, TimeUnit.SECONDS));
                var failure = assertThrows(ExecutionException.class, () -> events.get(5, TimeUnit.SECONDS));
                assertInstanceOf(ProtocolException.class, failure.getCause());
                assertEquals("MISSING_RESULT", failure.getCause().getMessage());
            }
            assertTrue(observed.stream().noneMatch(e -> e.type().equals("RESULT")
                    && e.payload().path("status").asText().equals("SUCCEEDED")));
        }

        void assertResourcesRemoved() throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (!containers(runId).isEmpty() && System.nanoTime() < deadline) Thread.sleep(200);
            assertTrue(containers(runId).isEmpty(), "Run-owned containers remain after worker termination");
        }

        public void close() throws Exception {
            try {
                if (process.isAlive()) process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            } finally {
                io.shutdownNow();
                assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
                // Test hygiene after failed assertions; removal assertions always precede this fallback.
                for (var container : containers(runId))
                    DockerClientFactory.instance().client().removeContainerCmd(container.getId()).withForce(true).withRemoveVolumes(true).exec();
            }
        }
    }
}
