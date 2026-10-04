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
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DeadLetterIntegrationTest {
    @TempDir Path root;
    private PipelineExperiment experiment() { return experiment(false); }
    private PipelineExperiment experiment(boolean oversized) {
        var properties = new PipelineProperties();
        properties.getQueue().setRawCapacity(1); properties.getQueue().setMappedCapacity(1);
        properties.getThreads().setValidator(2); properties.getThreads().setInserter(1); properties.getInsert().setChunkSize(2);
        var ds = new DriverManagerDataSource("jdbc:h2:mem:dlq_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table ingest_item(id bigint generated always as identity, col1 varchar(255), col2 varchar(255), col3 integer)");
        var reader = new ExcelStreamingReader() {
            @Override public int readFirstSheet(java.io.InputStream input, java.util.function.Consumer<RawRow> rows) {
                if (oversized) { rows.accept(new RawRow(1, List.of("", "한".repeat(22000), "bad"))); return 1; }
                for (int i = 1; i <= 30; i++) rows.accept(new RawRow(i, List.of("", "한글\nquoted\"", "bad")));
                rows.accept(new RawRow(31, List.of("ok", "ok", "1")));
                return 31;
            }
        };
        return new PipelineExperiment(new PipelineRunner(properties, reader, jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), 0));
    }
    private DurableExperimentRunner runner(RunStore store) {
        return new DurableExperimentRunner(store, () -> new GitProvenance("test", false, null), new MetricWriterOptions(8, 4, 20, 2, 1, 2000), 5, 2);
    }

    @Test void oversizedEvidenceFailsRunWithoutTruncatedOutput() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            try (var store = new JdbcRunStore(root, 100, 4); var runner = runner(store)) {
                for (var mode : PipelineRunner.RunMode.values()) {
                    var result = runner.run(experiment(true), new PipelineExperimentScenario(new byte[0], mode, point -> {}, new PipelineExperimentCapture()), mode.name());
                    assertEquals(ExperimentStatus.FAILED, result.status());
                    assertTrue(store.deadLetters(mode.name(), -1, 100).isEmpty());
                    assertEquals("DATABASE_V1", result.runMetadata().parameters().get("evidence.dead-letter.mode"));
                }
            }
        });
    }

    @Test void bothModesPersistFullRowContextAndStorageFailureCannotSucceed() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            try (var real = new JdbcRunStore(root, 100, 4)) {
                for (var mode : PipelineRunner.RunMode.values()) {
                    for (boolean fail : new boolean[]{false, true}) {
                        var store = spy(real);
                        if (fail) doAnswer(invocation -> {
                            DeadLetter row = invocation.getArgument(1);
                            if (row.rowIndex() >= 5) throw new IllegalStateException("injected disk failure");
                            return invocation.callRealMethod();
                        }).when(store).appendDeadLetter(anyString(), any());
                        try (var runner = runner(store)) {
                            String id = mode.name() + fail;
                            var result = runner.run(experiment(), new PipelineExperimentScenario(new byte[0], mode, point -> {}, new PipelineExperimentCapture()), id);
                            assertEquals(fail ? ExperimentStatus.FAILED : ExperimentStatus.SUCCEEDED, result.status());
                            var rows = real.deadLetters(id, -1, 100);
                            assertFalse(rows.isEmpty());
                            if (!fail) { assertEquals(30, rows.size()); assertEquals(30L, result.summary().counters().get("rows.validationErrors")); }
                            else assertTrue(rows.size() <= 4);
                            assertEquals(List.of("", "한글\nquoted\"", "bad"), rows.getFirst().cells());
                            assertEquals("col1 is required", rows.getFirst().reason());
                        }
                    }
                }
            }
            try (var store = new JdbcRunStore(root, 100, 4)) {
                assertEquals(30, store.deadLetters("PIPELINEfalse", -1, 100).size());
                assertEquals(RunStatus.FAILED, store.find("SINGLE_THREADtrue").orElseThrow().status());
                assertFalse(store.deadLetters("SINGLE_THREADtrue", -1, 100).isEmpty());
            }
        });
    }
}
