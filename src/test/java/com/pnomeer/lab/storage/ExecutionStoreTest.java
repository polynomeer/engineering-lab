package com.pnomeer.lab.storage;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionStoreTest {
    @TempDir Path root;
    private final RunExecution.Definition definition = new RunExecution.Definition(RunExecution.Kind.LOCAL_JVM,
            "career.example", 1, RunExecution.MeasurementScope.WORKER_PROCESS);
    private final RunExecution.Worker worker = new RunExecution.Worker(
            new RunEnvironment("21", "worker vendor", "worker vm", "os", "1", "arch", 2, 1024),
            new GitProvenance("worker-commit", false, null), "a".repeat(64));
    private RunMetadata metadata(String id) { return new RunMetadata(id, Map.of(), RunEnvironment.capture()); }
    private RunCheck check(int sequence) {
        return new RunCheck(sequence, "check-" + sequence, sequence % 2 == 0 ? RunCheck.Status.PASS : RunCheck.Status.FAIL,
                "중복 0", "관측 " + sequence, "expected/observed evidence");
    }
    private void start(JdbcRunStore store, String id) {
        store.create("test.execution", "small", metadata(id));
        store.registerExecution(id, definition); store.start(id); store.registerWorker(id, worker);
    }
    @Test void preservesWorkerIdentityAndConcurrentChecksAcrossReopenWithoutChangingControllerMetadata() throws Exception {
        RunMetadata controller;
        try (var store = new JdbcRunStore(root, 100, 10)) {
            start(store, "one");
            controller = store.find("one").orElseThrow().runMetadata();
            store.registerWorker("one", worker);
            store.registerExecution("one", definition);
            try (var executor = Executors.newFixedThreadPool(4)) {
                var futures = new ArrayList<Future<?>>();
                for (int i = 0; i < 20; i++) { int index = i; futures.add(executor.submit(() -> store.appendCheck("one", check(index)))); }
                for (var future : futures) future.get(5, TimeUnit.SECONDS);
            }
            store.appendCheck("one", check(0));
            store.advanceExecution("one", RunExecution.Phase.MEASURING);
            store.advanceExecution("one", RunExecution.Phase.MEASURING);
            store.advanceExecution("one", RunExecution.Phase.FINALIZING);
            store.finishExecutionEvidence("one");
            store.finishExecutionEvidence("one");
            store.complete("one", RunStatus.FAILED, ExperimentSummary.empty(), "Some checks failed");
        }
        try (var store = new JdbcRunStore(root, 100, 10)) {
            assertEquals(controller, store.find("one").orElseThrow().runMetadata());
            assertEquals(worker, store.execution("one").orElseThrow().worker());
            assertTrue(store.execution("one").orElseThrow().evidenceComplete());
            assertEquals(List.of(check(0), check(1)), store.checks("one", -1, 2));
            assertEquals(List.of(check(2), check(3)), store.checks("one", 1, 2));
            assertEquals(20, store.checks("one", -1, 100).size());
            assertTrue(store.checks("one", 19, 2).isEmpty());
            assertTrue(store.execution("missing").isEmpty());
            assertThrows(IllegalStateException.class, () -> store.appendCheck("one", check(20)));
            assertThrows(IllegalStateException.class, () -> store.registerWorker("one", worker));
            assertThrows(IllegalArgumentException.class, () -> store.checks("one", -2, 1));
            assertThrows(IllegalArgumentException.class, () -> store.checks("one", -1, 101));
        }
    }

    @Test void rejectsConflictsInvalidPhasesUnknownWorkersAndIncompleteSuccess() {
        try (var store = new JdbcRunStore(root, 100, 10)) {
            store.create("test", "small", metadata("one"));
            store.registerExecution("one", definition);
            assertNull(store.execution("one").orElseThrow().worker());
            assertThrows(IllegalStateException.class, () -> store.registerWorker("one", worker));
            store.start("one");
            assertThrows(IllegalStateException.class, () -> store.appendCheck("one", check(0)));
            assertThrows(IllegalStateException.class, () -> store.advanceExecution("one", RunExecution.Phase.MEASURING));
            assertThrows(IllegalStateException.class, () -> store.complete("one", RunStatus.SUCCEEDED, ExperimentSummary.empty(), null));
            store.registerWorker("one", worker);
            assertThrows(IllegalStateException.class, () -> store.registerWorker("one",
                    new RunExecution.Worker(worker.environment(), worker.git(), "b".repeat(64))));
            assertThrows(IllegalStateException.class, () -> store.registerExecution("one",
                    new RunExecution.Definition(RunExecution.Kind.IN_PROCESS, "local", 0, RunExecution.MeasurementScope.CONTROLLER_PROCESS)));
            store.appendCheck("one", check(0));
            assertThrows(IllegalStateException.class, () -> store.appendCheck("one", new RunCheck(0, "changed", RunCheck.Status.PASS, "", "", "")));
            assertThrows(IllegalStateException.class, () -> store.appendCheck("one", new RunCheck(1, "check-0", RunCheck.Status.PASS, "", "", "")));
            assertThrows(IllegalStateException.class, () -> store.advanceExecution("one", RunExecution.Phase.FINALIZING));
            assertThrows(IllegalStateException.class, () -> store.finishExecutionEvidence("one"));
            store.advanceExecution("one", RunExecution.Phase.MEASURING);
            assertThrows(IllegalStateException.class, () -> store.advanceExecution("one", RunExecution.Phase.PREPARING));
            store.advanceExecution("one", RunExecution.Phase.FINALIZING);
            store.finishExecutionEvidence("one");
            assertThrows(IllegalStateException.class, () -> store.appendCheck("one", check(1)));
            store.complete("one", RunStatus.SUCCEEDED, ExperimentSummary.empty(), null);
            assertEquals(RunStatus.SUCCEEDED, store.find("one").orElseThrow().status());
        }
    }

    @Test void recoveryRetainsPartialChecksAndUnknownIdentityButClearsUncommittedCompleteness() {
        try (var store = new JdbcRunStore(root, 100, 10)) {
            start(store, "partial"); store.appendCheck("partial", check(0));
            store.advanceExecution("partial", RunExecution.Phase.MEASURING);
            store.advanceExecution("partial", RunExecution.Phase.FINALIZING);
            store.finishExecutionEvidence("partial");
            store.create("test", "small", metadata("unknown")); store.registerExecution("unknown", definition);
        }
        try (var store = new JdbcRunStore(root, 100, 10)) {
            assertEquals(2, store.recoverInterrupted());
            assertEquals(0, store.recoverInterrupted());
            assertFalse(store.execution("partial").orElseThrow().evidenceComplete());
            assertEquals(List.of(check(0)), store.checks("partial", -1, 10));
            assertNull(store.execution("unknown").orElseThrow().worker());
            assertEquals(RunStatus.INTERRUPTED, store.find("unknown").orElseThrow().status());
        }
    }

    @Test void enforcesCheckBoundsWithoutTruncationAndAllowsInProcessEvidenceWithoutWorker() {
        try (var store = new JdbcRunStore(root, 200, 10)) {
            store.create("test", "small", metadata("one"));
            store.registerExecution("one", new RunExecution.Definition(RunExecution.Kind.IN_PROCESS, "local", 0,
                    RunExecution.MeasurementScope.CONTROLLER_PROCESS)); store.start("one");
            assertThrows(IllegalStateException.class, () -> store.registerWorker("one", worker));
            String escaped = "\u0000".repeat(4096);
            assertThrows(IllegalArgumentException.class, () -> store.appendCheck("one", new RunCheck(0, "large", RunCheck.Status.PASS, escaped, escaped, escaped)));
            assertTrue(store.checks("one", -1, 100).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> new RunCheck(RunCheck.MAX_CHECKS, "over", RunCheck.Status.PASS, "", "", ""));
            assertThrows(IllegalArgumentException.class, () -> new RunCheck(0, "over", RunCheck.Status.PASS, "x".repeat(4097), "", ""));
            assertThrows(IllegalArgumentException.class, () -> store.checks("one", -1, 101));
            store.appendCheck("one", check(RunCheck.MAX_CHECKS - 1));
            assertEquals(1, store.checks("one", -1, 100).size());
        }
    }
}
