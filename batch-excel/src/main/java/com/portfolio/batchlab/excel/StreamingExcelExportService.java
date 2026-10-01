package com.portfolio.batchlab.excel;

import com.portfolio.batchlab.batch.BatchProperties;
import com.portfolio.batchlab.domain.SettlementRecord;
import com.portfolio.batchlab.repository.SettlementRecordRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * [FACT 기반 재현] facts 54행 Decision — "Apache POI SXSSF Streaming API 적용.
 * 대량 조회를 청크 단위로 나누어 스트리밍 방식으로 파일 생성"을 재현하는
 * "after" 구현이다.
 *
 * design/batch-excel-optimization.md 5.4절·8장에서 새로 정한 구체값을 그대로
 * 쓴다 — window size 500행, 조회 페이지 2,000건, setCompressTempFiles(true),
 * 헤더 스타일 1회 생성 후 재사용, autoSizeColumn() 미사용(고정 컬럼 너비).
 * 이 구체값들은 facts에 없는 [NEW-DESIGN]이다.
 */
@Service
@RequiredArgsConstructor
public class StreamingExcelExportService {

    private static final String[] HEADERS = {"ID", "구독ID", "정산월", "총액", "수수료", "순액"};
    private static final int[] COLUMN_WIDTHS = {3000, 3000, 3000, 4000, 4000, 4000}; // [NEW-DESIGN] 고정 너비

    private final SettlementRecordRepository repository;

    public Path export(String targetMonth) throws IOException {
        Path outputPath = Files.createTempFile("settlement-streaming-" + targetMonth, ".xlsx");

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(BatchProperties.SXSSF_WINDOW_SIZE)) {
            workbook.setCompressTempFiles(true); // [NEW-DESIGN] 임시파일 디스크 사용량 절감
            Sheet sheet = workbook.createSheet("정산내역");

            for (int i = 0; i < COLUMN_WIDTHS.length; i++) {
                sheet.setColumnWidth(i, COLUMN_WIDTHS[i]); // autoSizeColumn() 대신 고정값 [NEW-DESIGN]
            }

            CellStyle headerStyle = createHeaderStyle(workbook); // 스타일은 1회 생성 후 재사용 [NEW-DESIGN]
            writeHeaderRow(sheet, headerStyle);

            int rowNum = 1;
            long lastSeenId = 0L;
            List<SettlementRecord> page;
            do {
                page = repository.findByTargetMonthAndSettlementStatusAndIdGreaterThanOrderByIdAsc(
                        targetMonth,
                        SettlementRecord.Status.AGGREGATED,
                        lastSeenId,
                        PageRequest.of(0, BatchProperties.EXCEL_QUERY_CHUNK_SIZE));

                for (SettlementRecord record : page) {
                    writeDataRow(sheet, rowNum++, record); // 쓰고 나면 이 행은 window를 벗어나면 재접근 불가
                }
                if (!page.isEmpty()) {
                    lastSeenId = page.get(page.size() - 1).getId();
                }
            } while (!page.isEmpty());

            try (OutputStream out = Files.newOutputStream(outputPath)) {
                workbook.write(out);
            } finally {
                workbook.dispose(); // 임시파일 정리 [NEW-DESIGN]
            }
        }
        return outputPath;
    }

    private CellStyle createHeaderStyle(SXSSFWorkbook workbook) {
        Font boldFont = workbook.createFont();
        boldFont.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(boldFont);
        return style;
    }

    private void writeHeaderRow(Sheet sheet, CellStyle headerStyle) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < HEADERS.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(HEADERS[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private void writeDataRow(Sheet sheet, int rowNum, SettlementRecord record) {
        Row row = sheet.createRow(rowNum);
        row.createCell(0).setCellValue(record.getId());
        row.createCell(1).setCellValue(record.getSubscriptionId());
        row.createCell(2).setCellValue(record.getTargetMonth());
        row.createCell(3).setCellValue(record.getGrossAmount());
        row.createCell(4).setCellValue(record.getFeeAmount());
        row.createCell(5).setCellValue(record.getNetAmount());
    }
}
