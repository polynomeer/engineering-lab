package com.example.pipeline.tools;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TestWorkbookGenerator {
    private TestWorkbookGenerator() {
    }

    public static void main(String[] args) throws IOException {
        String outputPath = args.length > 0 ? args[0] : "build/test-data/sample.xlsx";
        int rowCount = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        if (rowCount < 1) {
            throw new IllegalArgumentException("rowCount must be >= 1");
        }

        Path output = Path.of(outputPath);
        Files.createDirectories(output.getParent() == null ? Path.of(".") : output.getParent());
        generate(output, rowCount);
        System.out.println("Created: " + output.toAbsolutePath() + " rows=" + rowCount);
    }

    private static void generate(Path output, int rowCount) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
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

            try (OutputStream out = Files.newOutputStream(output)) {
                workbook.write(out);
            }
        }
    }
}
