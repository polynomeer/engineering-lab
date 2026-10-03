package com.pnomeer.lab.experiments.pipeline;

import com.pnomeer.lab.experiments.pipeline.config.PipelineProperties;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineRunnerIntegrationTest {

    @Test
    void runsPipelineAndCollectsValidationErrors() throws Exception {
        PipelineProperties properties = new PipelineProperties();
        properties.getQueue().setRawCapacity(8);
        properties.getQueue().setMappedCapacity(8);
        properties.getThreads().setValidator(3);
        properties.getThreads().setInserter(2);
        properties.getInsert().setChunkSize(2);
        properties.getBackpressure().setOfferTimeoutMs(200L);

        DataSource dataSource = createDataSource();
        initializeSchema(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        byte[] xlsx = createTestWorkbook();
        PipelineRunner runner = new PipelineRunner(properties, jdbcTemplate, transactionTemplate);
        PipelineRunner.PipelineRunResult result = runner.run(new ByteArrayInputStream(xlsx));

        int expectedProduced = 5;
        int expectedErrors = 2;
        int expectedInserted = 3;

        assertEquals(expectedProduced, result.getProducedCount());
        assertEquals(expectedInserted, result.getInsertedCount());
        assertFalse(result.getValidationErrors().isEmpty());
        assertEquals(expectedErrors, result.getValidationErrors().size());
        assertEquals(expectedInserted, jdbcTemplate.queryForObject("select count(*) from ingest_item", Integer.class));
    }

    @Test
    void backpressureAppearsWithSmallQueuesAndSlowInsert() throws Exception {
        PipelineProperties properties = new PipelineProperties();
        properties.getQueue().setRawCapacity(5);
        properties.getQueue().setMappedCapacity(5);
        properties.getThreads().setValidator(2);
        properties.getThreads().setInserter(1);
        properties.getInsert().setChunkSize(1);
        properties.getBackpressure().setOfferTimeoutMs(500L);

        DataSource dataSource = createDataSource();
        initializeSchema(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        int rowCount = 40;
        byte[] xlsx = createValidWorkbook(rowCount);
        PipelineRunner runner = new PipelineRunner(properties, jdbcTemplate, transactionTemplate, 25L);
        PipelineRunner.PipelineRunResult result = runner.run(new ByteArrayInputStream(xlsx));

        assertEquals(rowCount, result.getProducedCount());
        assertEquals(rowCount, result.getInsertedCount());
        assertEquals(0, result.getValidationErrors().size());
        assertEquals(rowCount, jdbcTemplate.queryForObject("select count(*) from ingest_item", Integer.class));
        assertTrue(result.getRawChannelEnqueueWaitNanos() > 1_000_000L);
        assertTrue(result.getElapsedMillis() >= 500L);
    }

    @Test
    void pipelineAndSingleThreadModesProduceSameResult() throws Exception {
        PipelineProperties properties = new PipelineProperties();
        properties.getQueue().setRawCapacity(8);
        properties.getQueue().setMappedCapacity(8);
        properties.getThreads().setValidator(2);
        properties.getThreads().setInserter(2);
        properties.getInsert().setChunkSize(2);
        properties.getBackpressure().setOfferTimeoutMs(200L);

        byte[] xlsx = createTestWorkbook();

        DataSource pipelineDs = createDataSource();
        initializeSchema(pipelineDs);
        JdbcTemplate pipelineJdbc = new JdbcTemplate(pipelineDs);
        TransactionTemplate pipelineTx = new TransactionTemplate(new DataSourceTransactionManager(pipelineDs));
        PipelineRunner pipelineRunner = new PipelineRunner(properties, pipelineJdbc, pipelineTx);
        PipelineRunner.PipelineRunResult pipelineResult =
                pipelineRunner.run(new ByteArrayInputStream(xlsx), PipelineRunner.RunMode.PIPELINE, null);

        DataSource singleDs = createDataSource();
        initializeSchema(singleDs);
        JdbcTemplate singleJdbc = new JdbcTemplate(singleDs);
        TransactionTemplate singleTx = new TransactionTemplate(new DataSourceTransactionManager(singleDs));
        PipelineRunner singleRunner = new PipelineRunner(properties, singleJdbc, singleTx);
        PipelineRunner.PipelineRunResult singleResult =
                singleRunner.run(new ByteArrayInputStream(xlsx), PipelineRunner.RunMode.SINGLE_THREAD, null);

        assertEquals(pipelineResult.getProducedCount(), singleResult.getProducedCount());
        assertEquals(pipelineResult.getInsertedCount(), singleResult.getInsertedCount());
        assertEquals(pipelineResult.getValidationErrors().size(), singleResult.getValidationErrors().size());
        assertEquals(pipelineResult.getInsertedCount(), pipelineJdbc.queryForObject("select count(*) from ingest_item", Integer.class));
        assertEquals(singleResult.getInsertedCount(), singleJdbc.queryForObject("select count(*) from ingest_item", Integer.class));
    }

    @Test
    void partialBatchFlushesWhileProducerIsIdleBeforeEndSignal() throws Exception {
        timedScenario(false, PipelineRunner.RunMode.PIPELINE);
    }

    @Test
    void arrivingRowsDoNotExtendTheFirstRowsDeadline() throws Exception {
        timedScenario(true, PipelineRunner.RunMode.PIPELINE);
    }

    @Test
    void singleThreadChecksDeadlineOnInvalidRowAndFlushesFinalPartialBatch() throws Exception {
        timedScenario(false, PipelineRunner.RunMode.SINGLE_THREAD);
    }

    private void timedScenario(boolean continuous, PipelineRunner.RunMode mode) throws Exception {
        var properties = new PipelineProperties();
        properties.getQueue().setRawCapacity(8);
        properties.getQueue().setMappedCapacity(8);
        properties.getThreads().setValidator(1);
        properties.getThreads().setInserter(2);
        properties.getInsert().setChunkSize(10000);
        properties.getInsert().setFlushIntervalMs(40);
        DataSource ds = createDataSource(); initializeSchema(ds);
        var jdbc = new JdbcTemplate(ds);
        var produced = new java.util.concurrent.atomic.AtomicInteger();
        var reader = new com.pnomeer.lab.experiments.pipeline.parse.ExcelStreamingReader() {
            @Override public int readFirstSheet(java.io.InputStream input,
                    java.util.function.Consumer<com.pnomeer.lab.experiments.pipeline.model.RawRow> sink) throws java.io.IOException {
                sink.accept(new com.pnomeer.lab.experiments.pipeline.model.RawRow(produced.incrementAndGet(), java.util.List.of("a","b","1")));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                try {
                    if (mode == PipelineRunner.RunMode.SINGLE_THREAD) {
                        Thread.sleep(70);
                        sink.accept(new com.pnomeer.lab.experiments.pipeline.model.RawRow(produced.incrementAndGet(), java.util.List.of("","b","1")));
                        assertEquals(1,jdbc.queryForObject("select count(*) from ingest_item",Integer.class));
                    } else {
                        while (jdbc.queryForObject("select count(*) from ingest_item",Integer.class) == 0) {
                            assertTrue(System.nanoTime() < deadline, "Partial batch must commit before producer completion");
                            if (continuous) sink.accept(new com.pnomeer.lab.experiments.pipeline.model.RawRow(produced.incrementAndGet(), java.util.List.of("a","b","1")));
                            Thread.sleep(5);
                        }
                    }
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new java.io.IOException(ex); }
                // Normal shutdown must persist this final partial chunk too.
                sink.accept(new com.pnomeer.lab.experiments.pipeline.model.RawRow(produced.incrementAndGet(), java.util.List.of("last","b","2")));
                return produced.get();
            }
        };
        var runner = new PipelineRunner(properties, reader, jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), 0);
        var result = runner.run(new ByteArrayInputStream(new byte[0]),mode,null);
        int expected = produced.get() - (mode == PipelineRunner.RunMode.SINGLE_THREAD ? 1 : 0);
        assertEquals(expected,result.getInsertedCount());
        assertEquals(expected,jdbc.queryForObject("select count(*) from ingest_item",Integer.class));
        assertEquals("40",runner.configurationSnapshot().get("pipeline.insert.flush-interval-ms"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> properties.getInsert().setFlushIntervalMs(0));
    }

    @Test
    void retriesRolledBackBatchesInBothModesAndUnblocksFullQueuesOnExhaustion() throws Exception {
        byte[] workbook = createValidWorkbook(100);
        for (var mode : PipelineRunner.RunMode.values()) {
            for (boolean exhaust : new boolean[]{false, true}) {
                DataSource ds = createDataSource(); initializeSchema(ds);
                var attempts = new java.util.concurrent.atomic.AtomicInteger();
                var jdbc = new JdbcTemplate(ds) {
                    @Override public <T> int[][] batchUpdate(String sql, java.util.Collection<T> rows, int size,
                            org.springframework.jdbc.core.ParameterizedPreparedStatementSetter<T> setter) {
                        int[][] result = super.batchUpdate(sql, rows, size, setter);
                        if (attempts.incrementAndGet() == 1 || exhaust) {
                            throw new org.springframework.dao.CannotAcquireLockException("injected after batch write");
                        }
                        return result;
                    }
                };
                var properties = new PipelineProperties();
                properties.getQueue().setRawCapacity(1); properties.getQueue().setMappedCapacity(1);
                properties.getThreads().setValidator(1); properties.getThreads().setInserter(1);
                properties.getInsert().setChunkSize(10); properties.getInsert().setRetryBackoffMs(1);
                var runner = new PipelineRunner(properties, jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                    if (exhaust) {
                        var failure = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                                () -> runner.run(new ByteArrayInputStream(workbook), mode, null));
                        Throwable root = failure;
                        while (root.getCause() != null) root = root.getCause();
                        org.junit.jupiter.api.Assertions.assertInstanceOf(org.springframework.dao.CannotAcquireLockException.class, root);
                        assertEquals(3, attempts.get());
                        assertEquals(0, jdbc.queryForObject("select count(*) from ingest_item", Integer.class));
                    } else {
                        var result = runner.run(new ByteArrayInputStream(workbook), mode, null);
                        assertEquals(100, result.getInsertedCount());
                        assertEquals(1, result.getRetryCount());
                        assertEquals(100, jdbc.queryForObject("select count(*) from ingest_item", Integer.class));
                    }
                });
            }
        }
    }

    private static byte[] createTestWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("input");

            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("col1");
            header.createCell(1).setCellValue("col2");
            header.createCell(2).setCellValue("col3Int");

            var row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("a1");
            row1.createCell(1).setCellValue("b1");
            row1.createCell(2).setCellValue(1);

            var row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("");
            row2.createCell(1).setCellValue("b2");
            row2.createCell(2).setCellValue(2);

            var row3 = sheet.createRow(3);
            row3.createCell(0).setCellValue("a3");
            row3.createCell(1).setCellValue("");
            row3.createCell(2).setCellValue(3);

            var row4 = sheet.createRow(4);
            row4.createCell(0).setCellValue("a4");
            row4.createCell(1).setCellValue("b4");
            row4.createCell(2).setCellValue(4);

            var row5 = sheet.createRow(5);
            row5.createCell(0).setCellValue("a5");
            row5.createCell(1).setCellValue("b5");
            row5.createCell(2).setCellValue(5);

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] createValidWorkbook(int rowCount) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("input");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("col1");
            header.createCell(1).setCellValue("col2");
            header.createCell(2).setCellValue("col3Int");

            for (int i = 1; i <= rowCount; i++) {
                var row = sheet.createRow(i);
                row.createCell(0).setCellValue("a" + i);
                row.createCell(1).setCellValue("b" + i);
                row.createCell(2).setCellValue(i);
            }

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static DataSource createDataSource() {
        String dbName = "pipeline_it_" + System.nanoTime();
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    private static void initializeSchema(DataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        populator.execute(dataSource);
    }
}
