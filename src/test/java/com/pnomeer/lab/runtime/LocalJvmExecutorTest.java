package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.*;
import com.pnomeer.lab.worker.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LocalJvmExecutorTest {
    @TempDir Path root;
    private final ExperimentScenario scenario = () -> "worker-scenario";
    private final Experiment<ExperimentScenario> experiment = new Experiment<>() {
        public String type() { return "fixture.sequence"; }
        public ExperimentSummary execute(ExperimentScenario scenario, Consumer<MetricPoint> points) {
            throw new AssertionError("External execution must never call the controller experiment body");
        }
    };
    private DurableExperimentRunner runner(RunStore store, String mode, Set<String> metrics) throws Exception {
        Path jar = Path.of(System.getProperty("lab.worker.fixture"));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        // Fixture is built for Java 21. Use a discovered Java 21 executable supplied by Gradle.
        Path java = Path.of(System.getProperty("lab.worker.java"));
        var manifest = new WorkerManifest(java, jar, hash, "fixture-module", Set.of("fixture.sequence"), 64);
        Path workspaces = Files.createDirectories(root.resolve("workspaces"));
        var executor = new LocalJvmExecutor(manifest, new WorkerProcess.Limits(Duration.ofSeconds(10), Duration.ofSeconds(1), 1024, ProtocolLimits.DEFAULT),
                workspaces, metrics, ignored -> new LocalJvmExecutor.Request(WireJson.object().put("mode", mode), "a".repeat(64), 1));
        var executors = new ExperimentExecutors("LOCAL_JVM", Map.of("LOCAL_JVM", new ExperimentExecutors.Registration(executor, 1)), Map.of());
        return new DurableExperimentRunner(store, () -> GitProvenance.unknown("controller"), new MetricWriterOptions(8, 4, 20, 2, 1, 2000), executors, 5, 2,
                Map.of("fixture.sequence", new RunExecution.Definition(RunExecution.Kind.LOCAL_JVM, "fixture-module", 1, RunExecution.MeasurementScope.WORKER_PROCESS)));
    }
    @Test void persistsWorkerIdentityMetricsAndChecksAndReopensCompleteEvidence() throws Exception {
        Path db = root.resolve("db");
        try (var store = new JdbcRunStore(db, 20, 4); var runner = runner(store, "evidence", Set.of("fixture.completed"))) {
            assertEquals(ExperimentStatus.SUCCEEDED, runner.run(experiment, scenario, "worker-success").status());
            var execution = store.execution("worker-success").orElseThrow();
            assertTrue(execution.evidenceComplete()); assertEquals(RunExecution.Kind.LOCAL_JVM, execution.definition().kind());
            assertTrue(execution.worker().environment().javaVersion().startsWith("21"));
            assertTrue(store.find("worker-success").orElseThrow().runMetadata().environment().javaVersion().startsWith("25"));
            assertEquals(1, store.metrics("worker-success", -1, 20).size());
            var check = store.checks("worker-success", -1, 20).getFirst();
            assertEquals(0, check.sequence()); assertEquals("unique", check.checkId());
        }
        try (var reopened = new JdbcRunStore(db, 20, 4)) {
            assertEquals(RunStatus.SUCCEEDED, reopened.find("worker-success").orElseThrow().status());
            assertTrue(reopened.execution("worker-success").orElseThrow().evidenceComplete());
            assertEquals(1, reopened.checks("worker-success", -1, 20).size());
        }
    }
    @Test void workerFailureRetainsPartialEvidenceWithoutSealing() throws Exception {
        try (var store = new JdbcRunStore(root.resolve("db"), 20, 4); var runner = runner(store, "evidence-failed", Set.of("fixture.completed"))) {
            assertEquals(ExperimentStatus.FAILED, runner.run(experiment, scenario, "failed").status());
            assertFalse(store.execution("failed").orElseThrow().evidenceComplete());
            assertEquals(1, store.metrics("failed", -1, 20).size()); assertEquals(1, store.checks("failed", -1, 20).size());
        }
    }
    @Test void checkPersistenceFailureCannotBecomeSuccessfulRun() throws Exception {
        try (var real = new JdbcRunStore(root.resolve("db"), 20, 4)) {
            var store = spy(real); doThrow(new IllegalStateException("injected")).when(store).appendCheck(eq("check-failure"), any());
            try (var runner = runner(store, "evidence", Set.of("fixture.completed"))) {
                assertEquals(ExperimentStatus.FAILED, runner.run(experiment, scenario, "check-failure").status());
                assertEquals(RunStatus.FAILED, real.find("check-failure").orElseThrow().status());
                assertFalse(real.execution("check-failure").orElseThrow().evidenceComplete());
            }
        }
    }
    @Test void metricFlushFailureCannotSealEvenAfterWorkerResult() throws Exception {
        try (var real = new JdbcRunStore(root.resolve("db"), 20, 4)) {
            var store = spy(real); doThrow(new IllegalStateException("injected")).when(store).appendMetrics(eq("flush-failure"), anyList());
            try (var runner = runner(store, "evidence", Set.of("fixture.completed"))) {
                assertEquals(ExperimentStatus.FAILED, runner.run(experiment, scenario, "flush-failure").status());
                assertFalse(real.execution("flush-failure").orElseThrow().evidenceComplete());
            }
        }
    }
    @Test void rejectsUnregisteredMetricAndDoesNotInventWorkerIdentityBeforeHello() throws Exception {
        try (var store = new JdbcRunStore(root.resolve("db"), 20, 4)) {
            try (var runner = runner(store, "evidence", Set.of())) {
                assertEquals(ExperimentStatus.FAILED, runner.run(experiment, scenario, "unknown-metric").status());
                assertEquals(0, store.metrics("unknown-metric", -1, 20).size());
            }
            try (var runner = runner(store, "missing", Set.of())) {
                assertEquals(ExperimentStatus.FAILED, runner.run(experiment, scenario, "no-hello").status());
                assertNull(store.execution("no-hello").orElseThrow().worker());
                assertFalse(store.execution("no-hello").orElseThrow().evidenceComplete());
            }
        }
    }
    @Test void workerIdentityPersistenceFailureLeavesNoInventedIdentity() throws Exception {
        try (var real = new JdbcRunStore(root.resolve("db"), 20, 4)) {
            var store = spy(real); doThrow(new IllegalStateException("injected")).when(store).registerWorker(eq("identity-failure"), any());
            try (var runner = runner(store, "evidence", Set.of("fixture.completed"))) {
                assertEquals(ExperimentStatus.FAILED, runner.run(experiment, scenario, "identity-failure").status());
                assertNull(real.execution("identity-failure").orElseThrow().worker());
                assertFalse(real.execution("identity-failure").orElseThrow().evidenceComplete());
            }
        }
    }
    @Test void refusesUnverifiedArtifactInsteadOfSilentlyDiscardingIt() throws Exception {
        try (var store = new JdbcRunStore(root.resolve("db"), 20, 4); var runner = runner(store, "artifact", Set.of())) {
            assertEquals(ExperimentStatus.FAILED, runner.run(experiment, scenario, "artifact").status());
            assertEquals("ARTIFACT_IMPORT_UNAVAILABLE", store.find("artifact").orElseThrow().failureMessage());
            assertFalse(store.execution("artifact").orElseThrow().evidenceComplete());
            assertTrue(store.artifacts("artifact").isEmpty());
            assertTrue(store.find("artifact").orElseThrow().summary().details().get("worker.workspace").startsWith("worker-"));
        }
    }

}
