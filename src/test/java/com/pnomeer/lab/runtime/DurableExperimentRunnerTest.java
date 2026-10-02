package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DurableExperimentRunnerTest {
    @TempDir Path root;
    private static final ExperimentScenario SCENARIO = () -> "small";
    private DurableExperimentRunner runner(RunStore store) {
        return new DurableExperimentRunner(store, () -> new GitProvenance("test-commit", false, null),
                new MetricWriterOptions(8, 4, 20, 2, 1, 2000), 5, 2);
    }
    private Experiment<ExperimentScenario> experiment(int count, boolean fail) {
        return new Experiment<>() {
            public String type() { return "test.metrics"; }
            public ExperimentSummary execute(ExperimentScenario scenario, java.util.function.Consumer<MetricPoint> sink) {
                for (int i = 0; i < count; i++) sink.accept(new MetricPoint(i, Map.of(), Map.of("index", (long)i), Map.of()));
                if (fail) throw new IllegalStateException("experiment failed");
                return new ExperimentSummary(Map.of("total", (long)count), Map.of(), Map.of());
            }
        };
    }
    @Test
    void storesAllMetricsWithBoundedPreviewAndPreservesFailureEvidence() {
        try (var store = new JdbcRunStore(root, 20, 4); var runner = runner(store)) {
            var result = runner.run(experiment(100, false), SCENARIO, "success");
            assertEquals(5, result.timeline().size());
            assertEquals(95L, result.timeline().getFirst().counters().get("index"));
            assertEquals(100L, result.summary().counters().get("storage.metrics.written"));
            assertTrue(result.summary().counters().get("storage.queue.maxDepth") <= 8);
            int count = 0;
            long cursor = -1;
            while (true) {
                var page = store.metrics("success", cursor, 20);
                if (page.isEmpty()) break;
                count += page.size(); cursor = page.getLast().sequence();
            }
            assertEquals(100, count);
            var failed = runner.run(experiment(3, true), SCENARIO, "failed");
            assertEquals(ExperimentStatus.FAILED, failed.status());
            assertEquals(3, store.metrics("failed", -1, 20).size());
            assertEquals("experiment failed", store.find("failed").orElseThrow().failureMessage());
            assertEquals("test-commit", failed.runMetadata().git().commit());
        }
    }
    @Test
    void neverReportsSuccessWhenMetricStorageFails() {
        try (var real = new JdbcRunStore(root, 20, 4)) {
            var store = spy(real);
            doThrow(new IllegalStateException("disk unavailable")).when(store).appendMetrics(anyString(), anyList());
            try (var runner = runner(store)) {
                var result = runner.run(experiment(1, false), SCENARIO, "storage-failed");
                assertEquals(ExperimentStatus.FAILED, result.status());
                assertTrue(result.failureMessage().contains("Observation storage failed"));
                assertEquals(RunStatus.FAILED, store.find("storage-failed").orElseThrow().status());
                assertEquals(1L, result.summary().counters().get("storage.failures"));
            }
        }
    }
    @Test
    void stopsNewSubmissionsButDrainsAlreadyPreparedWork() {
        try (var store = new JdbcRunStore(root, 20, 4); var runner = runner(store)) {
            var experiment = experiment(3, false);
            var prepared = runner.prepare(experiment, SCENARIO, "queued");
            runner.stopAccepting();
            assertThrows(IllegalStateException.class, () -> runner.prepare(experiment, SCENARIO, "late"));
            assertEquals(ExperimentStatus.SUCCEEDED, runner.runPrepared(experiment, SCENARIO, prepared).status());
            assertEquals(3, store.metrics("queued", -1, 20).size());
        }
    }
}
