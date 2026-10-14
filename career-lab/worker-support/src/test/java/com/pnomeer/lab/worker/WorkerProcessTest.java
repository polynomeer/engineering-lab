package com.pnomeer.lab.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkerProcessTest {
    @TempDir Path root;
    private WorkerManifest manifest() throws Exception {
        Path jar = Path.of(System.getProperty("worker.fixture.jar"));
        return new WorkerManifest(Path.of(System.getProperty("java.home"), "bin", "java"), jar,
                WorkerManifest.sha256(jar), "fixture-module", Set.of("fixture.sequence"), 64);
    }
    private ObjectNode command(String mode) {
        var command = WireJson.object().put("protocolVersion", 1).put("runId", "process-test").put("experimentId", "fixture.sequence")
                .put("definitionSha256", "a".repeat(64)).put("deadlineEpochMs", System.currentTimeMillis()+15000)
                .put("seed", 1).put("workspace", "/ignored");
        command.set("parameters", WireJson.object().put("mode", mode)); return command;
    }
    private WorkerProcess runner(int seconds) { return new WorkerProcess(new WorkerProcess.Limits(Duration.ofSeconds(seconds), Duration.ofSeconds(1), 1024, ProtocolLimits.DEFAULT)); }
    private final AtomicInteger events = new AtomicInteger();
    private final AtomicBoolean flushed = new AtomicBoolean();
    private WorkerProcess.EvidenceSink sink() {
        return new WorkerProcess.EvidenceSink() {
            public void persist(WorkerProtocol.Event event, Path workspace) throws Exception {
                Files.writeString(workspace.resolve("events"), event.type()+"\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                events.incrementAndGet();
            }
            public void flush() { flushed.set(true); }
        };
    }
    @Test void verifiedSeparateJvmStreamsEvidenceAndAcknowledgesFlush() throws Exception {
        var result = runner(10).execute(manifest(), command("success"), root, sink());
        assertEquals("SUCCEEDED", result.outcome().path("status").textValue());
        assertEquals(5, events.get()); assertTrue(flushed.get());
        assertNotEquals(ProcessHandle.current().pid(), Long.parseLong(Files.readString(result.workspace().resolve("pid"))));
        assertDead(result.workspace());
    }
    @Test void stderrIsDrainedBeyondRetentionWithoutDeadlock() throws Exception {
        var result = runner(10).execute(manifest(), command("stderr"), root, sink());
        assertEquals(2097152, result.stderrBytes()); assertEquals(1024, result.stderr().length); assertTrue(result.stderrTruncated());
    }
    @Test void reportedFailureIsNeverRewrittenAsSuccess() throws Exception {
        assertEquals("FAILED", runner(10).execute(manifest(), command("failed"), root, sink()).outcome().path("status").textValue());
    }
    @ParameterizedTest @ValueSource(strings={"missing", "malformed", "oversize", "sequence-gap", "phase-regression", "missing-result", "nonzero", "hang", "stubborn"})
    void brokenOrTimedOutChildCannotPassAndIsStopped(String mode) throws Exception {
        var failure = assertThrows(WorkerProcess.Failure.class, () -> runner((mode.equals("hang") || mode.equals("stubborn")) ? 2 : 10).execute(manifest(), command(mode), root, sink()));
        assertNotNull(failure.workspace()); assertDead(failure.workspace()); assertFalse(flushed.get());
    }
    @Test void storageFlushFailureLeavesPartialEvidenceAndStopsChild() throws Exception {
        var delegate = sink();
        var sink = new WorkerProcess.EvidenceSink() {
            public void persist(WorkerProtocol.Event event, Path workspace) throws Exception { delegate.persist(event, workspace); }
            public void flush() { throw new IllegalStateException("injected storage failure"); }
        };
        var failure = assertThrows(WorkerProcess.Failure.class, () -> runner(10).execute(manifest(), command("success"), root, sink));
        assertEquals("PIPE_OR_EVIDENCE_FAILED", failure.getMessage()); assertEquals(5, events.get()); assertDead(failure.workspace());
    }
    @Test void preflightRejectsWrongHashAndUnregisteredExperimentBeforeLaunch() throws Exception {
        var good = manifest(); var wrong = new WorkerManifest(good.javaExecutable(), good.jar(), "a".repeat(64), good.moduleId(), good.experiments(), 64);
        assertThrows(ProtocolException.class, () -> runner(10).execute(wrong, command("success"), root, sink()));
        var command = command("success"); command.put("experimentId", "other");
        assertThrows(ProtocolException.class, () -> runner(10).execute(good, command, root, sink()));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }
    @Test void workerParentMonitorDetectsControlEof() throws Exception {
        var lost = new AtomicBoolean();
        var watcher = ParentConnection.watch(new java.io.ByteArrayInputStream(new byte[0]), () -> lost.set(true));
        watcher.join(1000); assertTrue(lost.get()); assertFalse(watcher.isAlive());
    }
    @Test void actualWorkerStopsWhenParentClosesStdin() throws Exception {
        var manifest = manifest();
        Process process = new ProcessBuilder(manifest.javaExecutable().toString(), "-jar", manifest.jar().toString()).start();
        try {
            var command = command("hang"); command.put("workspace", root.toString());
            WorkerProtocol.writeCommand(process.getOutputStream(), command, ProtocolLimits.DEFAULT);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(root.resolve("pid")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(root.resolve("pid")));
            process.getOutputStream().close();
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(19, process.exitValue());
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }
    @Test void deadlineInterruptsBlockedEvidenceSinkAndStopsItsPublisher() throws Exception {
        var stopped = new AtomicBoolean();
        var sink = new WorkerProcess.EvidenceSink() {
            public void persist(WorkerProtocol.Event event, Path workspace) throws Exception {
                try { Thread.sleep(60000); } finally { stopped.set(true); }
            }
            public void flush() { fail("No flush after timeout"); }
        };
        var failure = assertThrows(WorkerProcess.Failure.class, () -> runner(2).execute(manifest(), command("success"), root, sink));
        assertEquals("DEADLINE_EXCEEDED", failure.getMessage()); assertTrue(stopped.get()); assertDead(failure.workspace());
    }
    @ParameterizedTest @ValueSource(strings={"descendant-success", "descendant-timeout"})
    void observedStubbornDescendantIsStoppedWithoutTouchingUnrelatedProcess(String mode) throws Exception {
        var manifest = manifest();
        Process unrelated = new ProcessBuilder(manifest.javaExecutable().toString(), "-jar", manifest.jar().toString(), "--descendant")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Path workspace = null;
        try {
            if (mode.endsWith("success")) workspace = runner(10).execute(manifest, command(mode), root, sink()).workspace();
            else workspace = assertThrows(WorkerProcess.Failure.class,
                    () -> runner(2).execute(manifest, command(mode), root, sink())).workspace();
            assertDead(workspace);
            long pid = Long.parseLong(Files.readString(workspace.resolve("descendant.pid")));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(unrelated.isAlive());
        } finally {
            unrelated.destroyForcibly().waitFor();
            // Also clean the fixture on assertion failure, without depending on production cleanup.
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.getFileName().toString().equals("descendant.pid")).toList()) {
                    stopFixtureChild(file);
                }
            }
        }
    }
    @Test void interruptionStillCleansObservedDescendantAndPreservesInterrupt() throws Exception {
        var manifest = manifest();
        var request = command("descendant-timeout");
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try { runner(10).execute(manifest, request, root, sink()); }
            catch (Throwable e) { failure.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        caller.start();
        Path childPid = null;
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (childPid == null && System.nanoTime() < deadline) {
                try (var files = Files.walk(root)) {
                    childPid = files.filter(p -> p.getFileName().toString().equals("descendant.pid")).findFirst().orElse(null);
                }
                Thread.sleep(20);
            }
            assertNotNull(childPid);
            // Allow observation before injecting caller interruption.
            Thread.sleep(300);
            caller.interrupt(); caller.join(5000);
            assertFalse(caller.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
            assertTrue(interrupted.get());
            assertDead(childPid.getParent());
            long pid = Long.parseLong(Files.readString(childPid));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            caller.interrupt(); caller.join(5000);
            if (childPid != null) stopFixtureChild(childPid);
        }
    }
    private void stopFixtureChild(Path pidFile) throws Exception {
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).ifPresent(handle -> {
            var arguments = handle.info().arguments();
            if (arguments.isPresent() && Arrays.asList(arguments.get()).contains(pidFile.getParent().resolve("worker.jar").toString())
                    && Arrays.asList(arguments.get()).contains("--descendant")) handle.destroyForcibly();
        });
    }
    private void assertDead(Path workspace) throws Exception {
        long pid = Long.parseLong(Files.readString(workspace.resolve("pid")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
}
