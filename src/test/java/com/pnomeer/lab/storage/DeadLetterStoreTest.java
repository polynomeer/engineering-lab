package com.pnomeer.lab.storage;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeadLetterStoreTest {
    @TempDir Path root;
    private RunMetadata metadata(String id) {
        return new RunMetadata(id, Map.of(), RunEnvironment.capture(), new GitProvenance("test", false, null));
    }
    private DeadLetter row(int index) { return new DeadLetter(index, "validation", "col3 must be integer", List.of("한글", "line\nquote\"", "bad")); }

    @Test void persistsConcurrentRowsAndPagesAcrossReopenWithImmutableRowKeys() throws Exception {
        try (var store = new JdbcRunStore(root, 100, 10)) {
            store.create("pipeline.excel-ingestion", "test", metadata("one"));
            assertThrows(IllegalStateException.class, () -> store.appendDeadLetter("one", row(1)));
            store.start("one");
            try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 1; i <= 20; i++) { int index = i; futures.add(executor.submit(() -> store.appendDeadLetter("one", row(index)))); }
                for (var future : futures) future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            store.appendDeadLetter("one", row(1));
            assertThrows(IllegalStateException.class, () -> store.appendDeadLetter("one", new DeadLetter(1, "validation", "different", List.of())));
            assertThrows(IllegalStateException.class, () -> store.appendDeadLetter("missing", row(1)));
            store.complete("one", RunStatus.FAILED, ExperimentSummary.empty(), "later insert failed");
            assertThrows(IllegalStateException.class, () -> store.appendDeadLetter("one", row(21)));
        }
        try (var store = new JdbcRunStore(root, 100, 10)) {
            assertEquals(20, store.deadLetters("one", -1, 100).size());
            assertEquals(List.of(row(1), row(2)), store.deadLetters("one", -1, 2));
            assertEquals(List.of(row(3), row(4)), store.deadLetters("one", 2, 2));
            assertTrue(store.deadLetters("one", 20, 2).isEmpty());
            assertTrue(store.deadLetters("other", -1, 2).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> store.deadLetters("one", -2, 2));
            assertThrows(IllegalArgumentException.class, () -> store.deadLetters("one", -1, 101));
        }
    }

    @Test void migratesVersionOneWithoutLosingRuns() throws Exception {
        try (var store = new JdbcRunStore(root, 10, 2)) { store.create("pipeline", "old", metadata("old")); }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:h2:file:" + root.resolve("runs"), "sa", ""); var statement = connection.createStatement()) {
            statement.execute("drop table lab_check"); statement.execute("drop table lab_execution");
            statement.execute("drop table lab_dead_letter"); statement.execute("update lab_schema set version=1 where id=1");
        }
        try (var store = new JdbcRunStore(root, 10, 2)) {
            assertEquals("old", store.find("old").orElseThrow().scenarioId());
            store.start("old"); store.appendDeadLetter("old", row(1));
        }
        try (var store = new JdbcRunStore(root, 10, 2)) { assertEquals(List.of(row(1)), store.deadLetters("old", -1, 2)); }
    }

    @Test void rejectsOversizedEncodedRowsWithoutTruncatingOrWritingThem() {
        try (var store = new JdbcRunStore(root, 10, 2)) {
            store.create("pipeline", "size", metadata("size")); store.start("size");
            var oversized = new DeadLetter(1, "validation", "invalid", List.of("한".repeat(22000)));
            assertThrows(IllegalArgumentException.class, () -> store.appendDeadLetter("size", oversized));
            assertTrue(store.deadLetters("size", -1, 10).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> new DeadLetter(1, "validation", "invalid", List.of("x".repeat(65536))));
        }
    }
}
