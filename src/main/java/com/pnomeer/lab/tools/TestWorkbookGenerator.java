package com.pnomeer.lab.tools;

import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes an ingestion workbook: header col1, col2, col3Int, then rowCount rows. With invalidEvery = N > 0,
 * every Nth row is invalid, alternating a blank col2 and a non-integer col3, so the expected dead-letter
 * count is rowCount / N.
 */
public final class TestWorkbookGenerator {
    private TestWorkbookGenerator() {
    }

    public static void main(String[] args) throws IOException {
        String outputPath = args.length > 0 ? args[0] : "build/test-data/sample.xlsx";
        int rowCount = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int invalidEvery = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        if (rowCount < 1) {
            throw new IllegalArgumentException("rowCount must be >= 1");
        }
        if (invalidEvery < 0) {
            throw new IllegalArgumentException("invalidEvery must be >= 0");
        }

        Path output = Path.of(outputPath);
        Files.createDirectories(output.getParent() == null ? Path.of(".") : output.getParent());
        generate(output, rowCount, invalidEvery);
        System.out.println("Created: " + output.toAbsolutePath() + " rows=" + rowCount
                + " invalid=" + (invalidEvery == 0 ? 0 : rowCount / invalidEvery));
    }

    private static void generate(Path output, int rowCount, int invalidEvery) throws IOException {
        // Streaming window: large fixtures do not need the whole sheet in memory.
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            var sheet = workbook.createSheet("input");

            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("col1");
            header.createCell(1).setCellValue("col2");
            header.createCell(2).setCellValue("col3Int");

            for (int i = 1; i <= rowCount; i++) {
                var row = sheet.createRow(i);
                boolean invalid = invalidEvery > 0 && i % invalidEvery == 0;
                row.createCell(0).setCellValue("a" + i);
                row.createCell(1).setCellValue(invalid && (i / invalidEvery) % 2 == 1 ? "" : "b" + i);
                if (invalid && (i / invalidEvery) % 2 == 0) row.createCell(2).setCellValue("x" + i);
                else row.createCell(2).setCellValue(i);
            }

            try (OutputStream out = Files.newOutputStream(output)) {
                workbook.write(out);
            }
        }
    }
}
