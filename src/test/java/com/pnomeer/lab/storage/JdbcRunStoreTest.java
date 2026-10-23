package com.pnomeer.lab.storage;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JdbcRunStoreTest {
    @TempDir Path root;
    private RunMetadata metadata(String id) {
        return new RunMetadata(id, Map.of("size", "10"), RunEnvironment.capture(), new GitProvenance("abc", true, null));
    }
    @Test
    void preservesResultsAndIdempotentMetricsAcrossReopenAndRecoversInterruptedRuns() {
        var point = new MetricPoint(123, Map.of("queue.depth", 2d), Map.of("count", 10L), Map.of());
        try (var store = new JdbcRunStore(root, 10, 2)) {
            store.create("queue", "small", metadata("complete"));
            store.start("complete");
            var batch = List.of(new StoredMetric(0, point), new StoredMetric(1, point));
            store.appendMetrics("complete", batch);
            store.appendMetrics("complete", batch);
            store.complete("complete", RunStatus.SUCCEEDED, new ExperimentSummary(Map.of("count",10L), Map.of(), Map.of()), null);
            store.create("queue", "pending", metadata("queued"));
            store.create("io", "active", metadata("running"));
            store.start("running");
            assertThrows(IllegalStateException.class, () -> store.start("complete"));
        }
        try (var store = new JdbcRunStore(root, 10, 2)) {
            assertEquals(2, store.recoverInterrupted());
            assertEquals(0, store.recoverInterrupted());
            var run = store.find("complete").orElseThrow();
            assertEquals(RunStatus.SUCCEEDED, run.status());
            assertEquals("10", run.runMetadata().parameters().get("size"));
            assertEquals("abc", run.runMetadata().git().commit());
            assertEquals(10L, run.summary().counters().get("count"));
            assertEquals(2, store.metrics("complete", -1, 10).size());
            assertEquals(point, store.metrics("complete", 0, 1).getFirst().point());
            assertEquals(RunStatus.INTERRUPTED, store.find("queued").orElseThrow().status());
            assertEquals(RunStatus.INTERRUPTED, store.find("running").orElseThrow().status());
            var page = store.list("queue", Long.MAX_VALUE, 1);
            assertEquals("queued", page.getFirst().runId());
            assertEquals("complete", store.list("queue", page.getFirst().sequence(), 1).getFirst().runId());
            assertThrows(IllegalArgumentException.class, () -> store.list(null, Long.MAX_VALUE, 11));
            assertThrows(IllegalArgumentException.class, () -> store.metrics("complete", -1, 0));
            assertThrows(IllegalArgumentException.class, () -> store.appendMetrics("complete", List.of(new StoredMetric(2,point),new StoredMetric(3,point),new StoredMetric(4,point))));
        }
    }

    @Test
    void preventsConcurrentOwnersAndRejectsUnknownSchemaVersions() throws Exception {
        try (var store = new JdbcRunStore(root, 10, 2)) {
            assertThrows(IllegalStateException.class, () -> new JdbcRunStore(root, 10, 2));
        }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:h2:file:" + root.resolve("runs"), "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("update lab_schema set version=99 where id=1");
        }
        assertThrows(IllegalStateException.class, () -> new JdbcRunStore(root, 10, 2));
    }

    @Test
    void filtersByScenarioPrefixAndStatusWithLikeWildcardsTakenLiterally() {
        try (var store = new JdbcRunStore(root, 10, 2)) {
            for (String id : List.of("sweep.p0", "sweep.p1", "sweepX", "other")) {
                store.create("queue", id, metadata(id));
                store.start(id);
                store.complete(id, id.equals("sweep.p1") ? RunStatus.FAILED : RunStatus.SUCCEEDED, ExperimentSummary.empty(), null);
            }
            store.create("queue", "s_x", metadata("underscore"));
            store.create("queue", "sax", metadata("plain"));
            var ids = (java.util.function.Function<List<StoredRun>, List<String>>) runs -> runs.stream().map(StoredRun::scenarioId).toList();
            assertEquals(List.of("sweep.p1", "sweep.p0"), ids.apply(store.list("queue", "sweep.", null, Long.MAX_VALUE, 10)));
            assertEquals(List.of("sweep.p1"), ids.apply(store.list(null, "sweep", RunStatus.FAILED, Long.MAX_VALUE, 10)));
            assertEquals(List.of("s_x"), ids.apply(store.list(null, "s_", null, Long.MAX_VALUE, 10))); // '_' is not a wildcard
            assertEquals(2, store.list(null, null, RunStatus.QUEUED, Long.MAX_VALUE, 10).size());
            assertEquals(List.of(), store.list("io", "sweep", null, Long.MAX_VALUE, 10));
        }
    }
}
