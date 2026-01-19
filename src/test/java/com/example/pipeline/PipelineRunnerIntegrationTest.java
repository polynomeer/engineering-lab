package com.example.pipeline;

import com.polynomeer.excelpipeline.config.PipelineProperties;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PipelineRunnerIntegrationTest {

    @Test
    void runsPipelineAndCollectsValidationErrors() throws Exception {
        PipelineProperties properties = new PipelineProperties();
        properties.getQueue().setRawCapacity(8);
        properties.getQueue().setMappedCapacity(8);
        properties.getThreads().setValidator(3);
        properties.getThreads().setInserter(2);
        properties.getBackpressure().setOfferTimeoutMs(200L);

        byte[] xlsx = createTestWorkbook();
        PipelineRunner runner = new PipelineRunner(properties);
        PipelineRunner.PipelineRunResult result = runner.run(new ByteArrayInputStream(xlsx));

        int expectedProduced = 5;
        int expectedErrors = 2;
        int expectedInserted = 3;

        assertEquals(expectedProduced, result.getProducedCount());
        assertEquals(expectedInserted, result.getInsertedCount());
        assertFalse(result.getValidationErrors().isEmpty());
        assertEquals(expectedErrors, result.getValidationErrors().size());
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
}
