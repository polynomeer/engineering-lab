package com.pnomeer.lab.experiments.pipeline;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.experiments.pipeline.config.PipelineProperties;
import com.pnomeer.lab.experiments.pipeline.model.RawRow;
import com.pnomeer.lab.experiments.pipeline.parse.ExcelStreamingReader;
import com.pnomeer.lab.runtime.DurableExperimentRunner;
import com.pnomeer.lab.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineLifecycleIntegrationTest {
    @TempDir Path root;
    private final DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:lifecycle_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
    private final JdbcTemplate jdbc = new JdbcTemplate(ds);
    PipelineLifecycleIntegrationTest() { jdbc.execute("create table ingest_item(col1 varchar(255), col2 varchar(255), col3 integer)"); }
    private PipelineRunner pipeline(ExcelStreamingReader reader, JdbcTemplate database, long timeout) {
        var properties = new PipelineProperties();
        properties.getQueue().setRawCapacity(2); properties.getQueue().setMappedCapacity(2);
        properties.getThreads().setValidator(2); properties.getThreads().setInserter(2);
        properties.getInsert().setChunkSize(100); properties.setShutdownTimeoutMs(timeout);
        return new PipelineRunner(properties, reader, database, new TransactionTemplate(new DataSourceTransactionManager(ds)), 0);
    }
    private ExcelStreamingReader source(boolean fail, boolean interrupt) {
        return new ExcelStreamingReader() {
            @Override public int readFirstSheet(InputStream input, java.util.function.Consumer<RawRow> rows) throws IOException {
                for (int i = 1; i <= 10; i++) rows.accept(new RawRow(i, List.of(i <= 7 ? "valid" : "", "b", "1")));
                if (interrupt) { Thread.currentThread().interrupt(); throw new InterruptedIOException("injected input stop"); }
                if (fail) throw new IOException("injected parse failure");
                return 10;
            }
        };
    }
    @Test void inputFailureAndInterruptionDrainAcceptedRowsAndPersistPartialRunSummary() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            try (var store = new JdbcRunStore(root, 20, 4);
                 var runner = new DurableExperimentRunner(store, () -> new GitProvenance("test", false, null), new MetricWriterOptions(8,4,20,2,1,2000), 5, 2)) {
                for (var mode : PipelineRunner.RunMode.values()) {
                    for (boolean interrupt : new boolean[]{false,true}) {
                        jdbc.update("delete from ingest_item");
                        String id = mode.name() + interrupt;
                        try {
                            runner.run(new PipelineExperiment(pipeline(source(true, interrupt), jdbc, 2000)),
                                    new PipelineExperimentScenario(new byte[0], mode, point -> {}, new PipelineExperimentCapture()), id);
                            assertEquals(interrupt, Thread.currentThread().isInterrupted());
                        } finally { Thread.interrupted(); }
                        var run = store.find(id).orElseThrow();
                        assertEquals(interrupt ? RunStatus.INTERRUPTED : RunStatus.FAILED, run.status());
                        assertEquals(7, jdbc.queryForObject("select count(*) from ingest_item", Integer.class));
                        assertEquals(7L, run.summary().counters().get("rows.inserted"));
                        assertEquals(3L, run.summary().counters().get("rows.validationErrors"));
                        assertEquals(0L, run.summary().counters().get("rows.unconfirmed"));
                        assertEquals("QUIESCENT", run.summary().details().get("shutdown.progress"));
                        assertEquals(3, store.deadLetters(id, -1, 20).size());
                        assertNoWorkers();
                    }
                }
            }
        });
    }
    @Test void drainTimeoutInterruptsWorkersAndReportsUnconfirmedRows() {
        var entered = new CountDownLatch(1);
        var slow = new JdbcTemplate(ds) {
            @Override public <T> int[][] batchUpdate(String sql, Collection<T> rows, int size, org.springframework.jdbc.core.ParameterizedPreparedStatementSetter<T> setter) {
                entered.countDown();
                try { Thread.sleep(5000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("injected cancelled DB call", ex); }
                return super.batchUpdate(sql, rows, size, setter);
            }
        };
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
            var failure = assertThrows(PartialExecutionFailure.class, () -> pipeline(source(false,false), slow, 100).run(new ByteArrayInputStream(new byte[0])));
            assertEquals(0, entered.getCount());
            assertEquals(7L, failure.summary().counters().get("rows.unconfirmed"));
            assertEquals(0L, failure.summary().counters().get("shutdown.unfinishedExecutors"));
            assertNoWorkers();
        });
    }
    @Test void coordinatorInterruptDuringInsertWaitStillFlushesAcceptedRows() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocking = new JdbcTemplate(ds) {
            @Override public <T> int[][] batchUpdate(String sql, Collection<T> rows, int size, org.springframework.jdbc.core.ParameterizedPreparedStatementSetter<T> setter) {
                entered.countDown();
                try { release.await(); } catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                return super.batchUpdate(sql, rows, size, setter);
            }
        };
        var observed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var restored = new java.util.concurrent.atomic.AtomicBoolean();
        var thread = new Thread(() -> {
            try { pipeline(source(false,false), blocking, 2000).run(new ByteArrayInputStream(new byte[0])); }
            catch (Throwable failure) { observed.set(failure); }
            finally { restored.set(Thread.currentThread().isInterrupted()); }
        });
        thread.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            thread.interrupt(); release.countDown(); thread.join(3000);
            assertFalse(thread.isAlive());
            var failure = assertInstanceOf(PartialExecutionFailure.class, observed.get());
            assertTrue(failure.interrupted()); assertTrue(restored.get());
            assertEquals(7L, failure.summary().counters().get("rows.inserted"));
            assertEquals(0L, failure.summary().counters().get("rows.unconfirmed"));
            assertNoWorkers();
        } finally { release.countDown(); thread.interrupt(); thread.join(3000); }
    }

    @Test void uncooperativeExecutorIsReportedWithoutClaimingTermination() throws Exception {
        var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { /* simulate an uncooperative driver */ }
            }
        });
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var lifecycle = new PipelineLifecycle(50);
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> assertEquals(1, lifecycle.stop(pool)));
            assertTrue(lifecycle.failed()); assertFalse(pool.isTerminated());
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    @Test void normalCompletionWaitsForInFlightProgressWithoutInterruptingIt() throws Exception {
        var publishing = new CountDownLatch(1);
        var finished = new java.util.concurrent.atomic.AtomicBoolean();
        var reader = new ExcelStreamingReader() {
            @Override public int readFirstSheet(InputStream input, java.util.function.Consumer<RawRow> rows) throws IOException {
                try { if (!publishing.await(2, TimeUnit.SECONDS)) throw new IOException("No progress callback"); }
                catch (InterruptedException ex) { throw new InterruptedIOException(); }
                return 0;
            }
        };
        var result = pipeline(reader, jdbc, 1000).run(new ByteArrayInputStream(new byte[0]), PipelineRunner.RunMode.PIPELINE, point -> {
            publishing.countDown();
            try { Thread.sleep(100); finished.set(true); }
            catch (InterruptedException ex) { throw new IllegalStateException("Progress interrupted", ex); }
        });
        assertEquals(0, result.getProducedCount()); assertTrue(finished.get()); assertNoWorkers();
    }

    @Test void progressListenerFailureCannotBecomeSuccessfulRun() {
        var slowReader = new ExcelStreamingReader() {
            @Override public int readFirstSheet(InputStream input, java.util.function.Consumer<RawRow> rows) throws IOException {
                try { Thread.sleep(400); } catch (InterruptedException ex) { throw new InterruptedIOException(); }
                rows.accept(new RawRow(1,List.of("a","b","1"))); return 1;
            }
        };
        assertThrows(PartialExecutionFailure.class, () -> pipeline(slowReader, jdbc, 1000).run(new ByteArrayInputStream(new byte[0]), PipelineRunner.RunMode.PIPELINE,
                point -> { throw new IllegalStateException("injected observation failure"); }));
        assertNoWorkers();
    }
    @Test void singleThreadFinalProgressFailurePreservesAcknowledgedCounts() {
        var reader = new ExcelStreamingReader() {
            @Override public int readFirstSheet(InputStream input, java.util.function.Consumer<RawRow> rows) throws IOException {
                try { Thread.sleep(250); } catch (InterruptedException ex) { throw new InterruptedIOException(); }
                return 0;
            }
        };
        var failure = assertThrows(PartialExecutionFailure.class, () -> pipeline(reader, jdbc, 1000).run(
                new ByteArrayInputStream(new byte[0]), PipelineRunner.RunMode.SINGLE_THREAD,
                point -> { throw new IllegalStateException("final progress failure"); }));
        assertEquals(0L, failure.summary().counters().get("rows.unconfirmed"));
        assertEquals("QUIESCENT", failure.summary().details().get("shutdown.progress"));
    }

    private static void assertNoWorkers() {
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() &&
                (t.getName().startsWith("pipeline-validator-") || t.getName().startsWith("pipeline-inserter-"))));
    }
}
