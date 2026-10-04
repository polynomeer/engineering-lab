package com.pnomeer.lab.experiments.pipeline.parse;

import com.pnomeer.lab.experiments.pipeline.model.RawRow;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Streaming XLSX parser for the first sheet only.
 * Supports InputStream input.
 */
public class ExcelStreamingReader {

    public int readFirstSheet(InputStream inputStream, Consumer<RawRow> rowConsumer) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream must not be null");
        Objects.requireNonNull(rowConsumer, "rowConsumer must not be null");

        var file = java.nio.file.Files.createTempFile("engineering-lab-parser-", ".xlsx");
        try {
            XlsxLimits.copy(inputStream, file);
            XlsxLimits.validate(file);
            return readFile(file, rowConsumer);
        } finally { java.nio.file.Files.deleteIfExists(file); }
    }

    private int readFile(java.nio.file.Path file, Consumer<RawRow> rowConsumer) throws IOException {
        try (OPCPackage opcPackage = OPCPackage.open(file.toFile(), org.apache.poi.openxml4j.opc.PackageAccess.READ)) {
            ReadOnlySharedStringsTable sharedStrings = new ReadOnlySharedStringsTable(opcPackage);
            XSSFReader reader = new XSSFReader(opcPackage);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            if (!sheets.hasNext()) {
                return 0;
            }

            AtomicInteger producedCount = new AtomicInteger(0);
            try (InputStream firstSheet = sheets.next()) {
                var parser = new org.xml.sax.helpers.XMLFilterImpl(org.apache.poi.util.XMLHelper.newXMLReader()) {
                    private int cellCharacters;
                    private boolean inCell;
                    @Override public void startElement(String uri, String local, String qName, org.xml.sax.Attributes attributes) throws org.xml.sax.SAXException {
                        if ("c".equals(local)) { inCell = true; cellCharacters = 0; }
                        super.startElement(uri, local, qName, attributes);
                    }
                    @Override public void characters(char[] chars, int start, int length) throws org.xml.sax.SAXException {
                        if (inCell && (cellCharacters += length) > XlsxLimits.MAX_ROW_CHARS) throw new org.xml.sax.SAXException("Cell text exceeds limit");
                        super.characters(chars, start, length);
                    }
                    @Override public void endElement(String uri, String local, String qName) throws org.xml.sax.SAXException {
                        super.endElement(uri, local, qName);
                        if ("c".equals(local)) inCell = false;
                    }
                };
                XSSFSheetXMLHandler.SheetContentsHandler handler =
                        new FirstSheetHandler(rowConsumer, producedCount);
                parser.setContentHandler(
                        new XSSFSheetXMLHandler(styles, null, sharedStrings, handler, new DataFormatter(), false));
                parser.parse(new InputSource(firstSheet));
            } catch (Exception ex) {
                throw new IOException("Failed to parse XLSX first sheet", ex);
            }

            return producedCount.get();
        } catch (Exception ex) {
            if (ex instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to open XLSX input", ex);
        }
    }

    private static final class FirstSheetHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final Consumer<RawRow> rowConsumer;
        private final AtomicInteger producedCount;
        private List<String> currentRow = List.of();
        private int currentCol = -1;
        private int currentRowNum = -1;
        private int rowCharacters;

        private FirstSheetHandler(Consumer<RawRow> rowConsumer, AtomicInteger producedCount) {
            this.rowConsumer = rowConsumer;
            this.producedCount = producedCount;
        }

        @Override
        public void startRow(int rowNum) {
            currentRowNum = rowNum;
            currentCol = -1;
            rowCharacters = 0;
            currentRow = new ArrayList<>();
        }

        @Override
        public void endRow(int rowNum) {
            if (rowNum == 0) {
                return;
            }
            rowConsumer.accept(new RawRow(rowNum, currentRow));
            producedCount.incrementAndGet();
        }

        @Override
        public void cell(String cellReference, String formattedValue, org.apache.poi.xssf.usermodel.XSSFComment comment) {
            int col = getColumnIndex(cellReference);
            if (col < 0 || col >= 16384 || col <= currentCol) throw new IllegalArgumentException("Invalid or excessive column index");
            rowCharacters += formattedValue == null ? 0 : formattedValue.length();
            if (rowCharacters > XlsxLimits.MAX_ROW_CHARS) throw new IllegalArgumentException("Row text exceeds limit");
            while (currentCol + 1 < col) {
                currentRow.add("");
                currentCol++;
            }
            currentRow.add(formattedValue == null ? "" : formattedValue);
            currentCol = col;
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
        }

        private static int getColumnIndex(String cellReference) {
            if (cellReference == null) {
                return 0;
            }
            return CellReference.convertColStringToIndex(cellReference.replaceAll("[0-9]", ""));
        }
    }
}
