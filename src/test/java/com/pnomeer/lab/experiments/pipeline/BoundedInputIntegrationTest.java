package com.pnomeer.lab.experiments.pipeline;

import com.pnomeer.lab.experiments.pipeline.config.PipelineProperties;
import com.pnomeer.lab.experiments.pipeline.parse.ExcelStreamingReader;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BoundedInputIntegrationTest {
    @TempDir Path root;
    @Test void largeInvalidWorkbookKeepsPreviewBoundedWithoutLosingCountsOrDeadLetters() throws Exception {
        Path file = root.resolve("large.xlsx");
        int count = 30000;
        try (var workbook = new SXSSFWorkbook(20); var out = Files.newOutputStream(file)) {
            var sheet = workbook.createSheet(); sheet.createRow(0).createCell(0).setCellValue("header");
            for (int i = 1; i <= count; i++) {
                var row = sheet.createRow(i); row.createCell(0).setCellValue(""); row.createCell(1).setCellValue("value-" + i); row.createCell(2).setCellValue("bad");
            }
            workbook.write(out);
        }
        for (var mode : PipelineRunner.RunMode.values()) {
            var properties = new PipelineProperties(); properties.setErrorPreviewLimit(7);
            properties.getQueue().setRawCapacity(8); properties.getQueue().setMappedCapacity(8);
            properties.getThreads().setValidator(3); properties.getThreads().setInserter(2); properties.getInsert().setChunkSize(10);
            var ds = new DriverManagerDataSource("jdbc:h2:mem:bounded_" + mode + System.nanoTime(), "sa", "");
            var runner = new PipelineRunner(properties, new JdbcTemplate(ds), new TransactionTemplate(new DataSourceTransactionManager(ds)));
            var persisted = new AtomicInteger();
            try (var input = Files.newInputStream(file)) {
                var result = runner.run(input, mode, null, error -> persisted.incrementAndGet());
                assertEquals(count, result.getProducedCount()); assertEquals(count, persisted.get());
                assertEquals(count, result.getValidationErrorCount()); assertEquals(7, result.getValidationErrors().size());
                assertEquals((long)count, result.getValidationReasons().get("col1 is required"));
            }
        }
    }
    @Test void rejectsOversizedMetadataBeforePoiAllocation() throws Exception {
        Path file = root.resolve("metadata.xlsx");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/sharedStrings.xml"));
            byte[] buffer = new byte[8192];
            for (int i = 0; i < 1025; i++) zip.write(buffer);
            zip.closeEntry();
        }
        try (var input = Files.newInputStream(file)) {
            var failure = assertThrows(java.io.IOException.class, () -> new ExcelStreamingReader().readFirstSheet(input, row -> fail("Must reject before parsing")));
            assertTrue(failure.getMessage().contains("expanded content"));
        }
    }
    @Test void zeroPreviewRetainsCountsOnly() {
        var errors = new ValidationErrors(0);
        var row = new com.pnomeer.lab.experiments.pipeline.model.RawRow(1, java.util.List.of(""));
        errors.add(new com.pnomeer.lab.experiments.pipeline.model.ValidationError(1, "col1 is required", row));
        assertTrue(errors.preview().isEmpty()); assertEquals(1L, errors.reasons().get("col1 is required"));
        assertThrows(IllegalArgumentException.class, () -> new PipelineProperties().setErrorPreviewLimit(1001));
    }
}
