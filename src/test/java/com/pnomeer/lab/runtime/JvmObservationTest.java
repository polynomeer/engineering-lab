package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.metrics.JvmSnapshot;
import com.pnomeer.lab.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JvmObservationTest {
    @TempDir Path root;
    private DurableExperimentRunner runner(RunStore store, java.util.function.Supplier<MetricPoint> probe) {
        return new DurableExperimentRunner(store, () -> new GitProvenance("test", false, null),
                new MetricWriterOptions(1,1,10,1,1,2000),1,1,probe);
    }
    private Experiment<ExperimentScenario> experiment(boolean fail) {
        return new Experiment<>() {
            public String type() { return "test"; }
            public ExperimentSummary execute(ExperimentScenario s, java.util.function.Consumer<MetricPoint> sink) {
                sink.accept(new MetricPoint(1, Map.of(), Map.of("rows.inserted", 7L), Map.of()));
                if (fail) throw new IllegalStateException("expected failure");
                return ExperimentSummary.empty();
            }
        };
    }
    @Test void persistsTwoProcessSamplesAndKeepsLegacyPreviewWithOnePointLimit() {
        try (var store = new JdbcRunStore(root,1,1); var runner = runner(store, JvmSnapshot::capture)) {
            var result = runner.run(experiment(false), () -> "test", "a");
            assertEquals(ExperimentStatus.SUCCEEDED, result.status());
            assertEquals("BOUNDARY", result.runMetadata().parameters().get("observation.jvm.mode"));
            assertEquals(2L, result.summary().counters().get("observation.jvm.samples"));
            assertTrue(result.summary().counters().get("observation.jvm.captureNanos") > 0);
            assertEquals(3L, result.summary().counters().get("storage.metrics.written"));
            assertEquals("start", store.metrics("a",-1,1).getFirst().point().labels().get("phase"));
            assertEquals("end", store.recentMetrics("a",1).getFirst().point().labels().get("phase"));
            assertEquals(7L, ExperimentMetricPreview.read(store,"a",1).getFirst().counters().get("rows.inserted"));
            assertEquals(7L, result.timeline().getFirst().counters().get("rows.inserted"));
        }
        try (var store = new JdbcRunStore(root,1,1)) {
            assertEquals(7L, ExperimentMetricPreview.read(store,"a",1).getFirst().counters().get("rows.inserted"));
            assertEquals("process", store.recentMetrics("a",1).getFirst().point().labels().get("scope"));
        }
    }
    @Test void recordsFailureBoundaryAndDoesNotHideCollectorFailure() {
        try (var store = new JdbcRunStore(root,10,1); var runner = runner(store, JvmSnapshot::capture)) {
            var result = runner.run(experiment(true), () -> "test", "failed");
            assertEquals(ExperimentStatus.FAILED, result.status());
            assertEquals("expected failure", result.failureMessage());
            assertEquals(2L, result.summary().counters().get("observation.jvm.samples"));
        }
        AtomicInteger attempts = new AtomicInteger();
        try (var store = new JdbcRunStore(root,10,1); var runner = runner(store, () -> {
            if (attempts.incrementAndGet() == 2) throw new IllegalStateException("collector failed");
            return JvmSnapshot.capture();
        })) {
            var result = runner.run(experiment(false), () -> "test", "probe-failed");
            assertEquals(ExperimentStatus.FAILED, result.status());
            assertTrue(result.failureMessage().contains("JVM observation failed"));
            assertEquals(1L, result.summary().counters().get("observation.jvm.samples"));
            assertEquals(2, store.metrics("probe-failed",-1,10).size());
        }
    }
    @Test void disabledObservationAddsNoSamplesAndSnapshotHasBoundedAllowlistedShape() {
        try (var store = new JdbcRunStore(root,10,1); var runner = runner(store,null)) {
            var result = runner.run(experiment(false), () -> "test", "off");
            assertEquals("OFF", result.runMetadata().parameters().get("observation.jvm.mode"));
            assertEquals(1, store.metrics("off",-1,10).size());
            assertFalse(result.summary().counters().containsKey("observation.jvm.samples"));
        }
        var point = JvmSnapshot.capture();
        assertTrue(point.gauges().size() <= 4);
        assertTrue(point.counters().size() <= 2);
        assertTrue(point.gauges().values().stream().allMatch(n -> n >= 0 && Double.isFinite(n)));
        assertTrue(point.counters().values().stream().allMatch(n -> n >= 0));
        assertTrue(point.labels().isEmpty());
    }
}
