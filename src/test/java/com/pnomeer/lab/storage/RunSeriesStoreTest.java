package com.pnomeer.lab.storage;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RunSeriesStoreTest {
    @TempDir Path root;
    private void run(JdbcRunStore store, String id) {
        store.create("queue", "small", new RunMetadata(id, Map.of(), RunEnvironment.capture(), new GitProvenance("abc", false, null)));
    }

    @Test void recordsOrderedMembershipAndCompletesOnlyOnce() {
        try (var store = new JdbcRunStore(root, 10, 2)) {
            var series = store.createSeries("s1", "queue", "small", "a".repeat(64), 2, 1);
            assertEquals(RunStatus.QUEUED, series.status());
            assertEquals(3, series.total());
            run(store, "warm"); run(store, "r1"); run(store, "r2"); run(store, "extra");
            assertThrows(IllegalStateException.class, () -> store.addSeriesRun("s1", new RunSeries.Member(0, "warm", true)));
            store.startSeries("s1");
            store.addSeriesRun("s1", new RunSeries.Member(1, "r1", false));
            store.addSeriesRun("s1", new RunSeries.Member(0, "warm", true));
            store.addSeriesRun("s1", new RunSeries.Member(2, "r2", false));
            assertThrows(IllegalArgumentException.class, () -> store.addSeriesRun("s1", new RunSeries.Member(3, "extra", false)));
            assertThrows(RuntimeException.class, () -> store.addSeriesRun("s1", new RunSeries.Member(2, "extra", false)));
            assertEquals(java.util.List.of(new RunSeries.Member(0, "warm", true), new RunSeries.Member(1, "r1", false),
                    new RunSeries.Member(2, "r2", false)), store.seriesRuns("s1"));
            store.completeSeries("s1", RunStatus.SUCCEEDED, null);
            assertThrows(IllegalStateException.class, () -> store.completeSeries("s1", RunStatus.FAILED, "again"));
            assertThrows(IllegalArgumentException.class, () -> store.completeSeries("s1", RunStatus.RUNNING, null));
            var done = store.findSeries("s1").orElseThrow();
            assertEquals(RunStatus.SUCCEEDED, done.status());
            assertTrue(done.completedAtEpochMs() > 0);
        }
    }

    @Test void listsNewestFirstByExperimentAndInterruptsUnfinishedSeriesOnRestart() {
        try (var store = new JdbcRunStore(root, 10, 2)) {
            store.createSeries("old", "queue", "a", "a".repeat(64), 1, 0);
            store.createSeries("other", "io", "b", "b".repeat(64), 1, 0);
            store.createSeries("new", "queue", "c", "c".repeat(64), 3, 0);
            store.startSeries("new");
            assertEquals(java.util.List.of("new", "old"), store.listSeries("queue", 10).stream().map(RunSeries::seriesId).toList());
            assertEquals(3, store.listSeries(null, 10).size());
            assertThrows(IllegalArgumentException.class, () -> store.createSeries("bad", "queue", "x", "d".repeat(64), 0, 0));
        }
        try (var store = new JdbcRunStore(root, 10, 2)) {
            assertEquals(3, store.recoverInterruptedSeries());
            assertEquals(0, store.recoverInterruptedSeries());
            var series = store.findSeries("new").orElseThrow();
            assertEquals(RunStatus.INTERRUPTED, series.status());
            assertEquals("Process stopped before the series finished", series.failureMessage());
        }
    }

    @Test void pinsOneBaselinePerScenarioAndOnlyForSucceededSeries() {
        try (var store = new JdbcRunStore(root, 10, 2)) {
            store.createSeries("first", "queue", "small", "a".repeat(64), 3, 0);
            store.createSeries("second", "queue", "small", "a".repeat(64), 3, 0);
            store.createSeries("running", "queue", "small", "a".repeat(64), 3, 0);
            assertThrows(IllegalStateException.class, () -> store.pinBaseline("first"));
            for (String id : java.util.List.of("first", "second")) { store.startSeries(id); store.completeSeries(id, RunStatus.SUCCEEDED, null); }
            store.pinBaseline("first");
            assertEquals("first", store.baseline("queue", "small").orElseThrow().seriesId());
            store.pinBaseline("second");
            assertEquals("second", store.baseline("queue", "small").orElseThrow().seriesId());
            assertEquals(1, store.baselines(10).size());
            assertTrue(store.baseline("queue", "other").isEmpty());
            assertThrows(IllegalArgumentException.class, () -> store.pinBaseline("missing"));
        }
    }

    @Test void recordsSweepPointsInOrderAndInterruptsUnfinishedSweeps() {
        try (var store = new JdbcRunStore(root, 10, 2)) {
            var grid = new java.util.LinkedHashMap<String, java.util.List<Object>>();
            grid.put("queueCapacity", java.util.List.of(1, 4));
            grid.put("mode", java.util.List.of("A", "B"));
            var sweep = store.createSweep("w1", "queue", "cap", grid, 3, 1);
            assertEquals(java.util.List.of("queueCapacity", "mode"), java.util.List.copyOf(sweep.grid().keySet()));
            store.createSeries("p0", "queue", "cap.p0", "a".repeat(64), 3, 1);
            store.createSeries("p1", "queue", "cap.p1", "b".repeat(64), 3, 1);
            assertThrows(IllegalStateException.class, () -> store.addSweepPoint("w1", new RunSweep.Point(0, "p0", java.util.Map.of("queueCapacity", 1))));
            store.startSweep("w1");
            store.addSweepPoint("w1", new RunSweep.Point(1, "p1", java.util.Map.of("queueCapacity", 4)));
            store.addSweepPoint("w1", new RunSweep.Point(0, "p0", java.util.Map.of("queueCapacity", 1)));
            assertEquals(java.util.List.of("p0", "p1"), store.sweepPoints("w1").stream().map(RunSweep.Point::seriesId).toList());
            assertEquals(4, store.sweepPoints("w1").get(1).parameters().get("queueCapacity"));
            assertEquals(1, store.listSweeps("queue", 10).size());
        }
        try (var store = new JdbcRunStore(root, 10, 2)) {
            assertEquals(3, store.recoverInterruptedSeries()); // the running sweep and its two queued series
            assertEquals(RunStatus.INTERRUPTED, store.findSweep("w1").orElseThrow().status());
        }
    }
}
