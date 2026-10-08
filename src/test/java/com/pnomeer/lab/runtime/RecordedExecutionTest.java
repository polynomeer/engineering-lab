package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RecordedExecutionTest {
    @TempDir Path root;
    private final ExperimentScenario scenario = () -> "small";
    private DurableExperimentRunner runner(RunStore store) {
        return new DurableExperimentRunner(store, () -> GitProvenance.unknown("test"),
                new MetricWriterOptions(8, 4, 20, 2, 1, 2000), 5, 2, null, 0, 10, false, 10,
                null, null, Map.of("test.recorded", "test-module"));
    }
    private Experiment<ExperimentScenario> experiment(boolean fail) {
        return new Experiment<>() {
            public String type() { return "test.recorded"; }
            public ExperimentSummary execute(ExperimentScenario scenario, Consumer<MetricPoint> sink) {
                sink.accept(new MetricPoint(1, Map.of(), Map.of("count", 1L), Map.of()));
                if (fail) throw new IllegalStateException("experiment failed");
                return ExperimentSummary.empty();
            }
        };
    }
    @Test void recordsPreparationAndFinalizationAndLeavesFailureEvidenceIncomplete() {
        try (var store = new JdbcRunStore(root, 20, 4); var runner = runner(store)) {
            var prepared = runner.prepare(experiment(false), scenario, "success");
            var preparing = store.execution("success").orElseThrow();
            assertEquals("test-module", preparing.definition().moduleId());
            assertEquals(RunExecution.Phase.PREPARING, preparing.phase());
            assertFalse(preparing.evidenceComplete());
            assertEquals(ExperimentStatus.SUCCEEDED, runner.runPrepared(experiment(false), scenario, prepared).status());
            var finished = store.execution("success").orElseThrow();
            assertEquals(RunExecution.Phase.FINALIZING, finished.phase());
            assertTrue(finished.evidenceComplete()); assertNull(finished.worker());
            assertEquals(ExperimentStatus.FAILED, runner.run(experiment(true), scenario, "failed").status());
            assertFalse(store.execution("failed").orElseThrow().evidenceComplete());
            assertEquals(1, store.metrics("failed", -1, 20).size());
        }
    }
    @Test void persistenceFailuresNeverSealOrReportSuccessfulEvidenceAndStillDrainMetrics() {
        try (var real = new JdbcRunStore(root, 20, 4)) {
            var store = spy(real);
            try (var runner = runner(store)) {
                doThrow(new IllegalStateException("phase failed")).when(store).advanceExecution("phase", RunExecution.Phase.FINALIZING);
                assertEquals(ExperimentStatus.FAILED, runner.run(experiment(false), scenario, "phase").status());
                assertEquals(1, store.metrics("phase", -1, 20).size());
                assertFalse(store.execution("phase").orElseThrow().evidenceComplete());
                doThrow(new IllegalStateException("seal failed")).when(store).finishExecutionEvidence("seal");
                assertEquals(ExperimentStatus.FAILED, runner.run(experiment(false), scenario, "seal").status());
                assertFalse(store.execution("seal").orElseThrow().evidenceComplete());
                doThrow(new IllegalStateException("writer failed")).when(store).appendMetrics(eq("writer"), anyList());
                assertEquals(ExperimentStatus.FAILED, runner.run(experiment(false), scenario, "writer").status());
                assertFalse(store.execution("writer").orElseThrow().evidenceComplete());
            }
        }
    }
    @Test void atomicCreationDoesNotLeaveQueuedRunWhenExecutionRegistrationFails() {
        try (var store = new JdbcRunStore(root, 20, 4)) {
            assertThrows(NullPointerException.class, () -> store.create("test", "small", new RunMetadata("rollback", Map.of(), RunEnvironment.capture()), null));
            assertTrue(store.find("rollback").isEmpty());
        }
    }
}
