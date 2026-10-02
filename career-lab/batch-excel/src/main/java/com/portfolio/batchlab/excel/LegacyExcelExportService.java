package com.portfolio.batchlab.excel;

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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * [FACT 기반 재현] facts 53행 Problem — "조회 결과를 한 번에 메모리에 적재한 뒤
 * 파일 생성 → 메모리 사용량 급증, OOM 발생"을 재현하는 "before" 구현이다.
 *
 * {@link #export}는 대상 정산월의 AGGREGATED 레코드 전체를 하나의 List로 로드한
 * 뒤(내부적으로 페이지를 나눠 조회하긴 하지만 리스트에 누적한다는 점이 핵심이다 —
 * 조회 자체를 한 방 쿼리로 하든 나눠서 하든, 결국 전체 데이터가 메모리 리스트에
 * 다 올라간다는 Problem은 동일하게 재현된다), XSSFWorkbook(POI의 전체 인메모리
 * 워크북 구현체)으로 모든 행을 한 번에 만든다. XSSFWorkbook은 모든 셀 객체를
 * 힙에 유지하므로, 레코드 수에 비례해 메모리 사용량이 선형으로 증가한다.
 */
@Service
@RequiredArgsConstructor
public class LegacyExcelExportService {

    private static final int FETCH_PAGE_SIZE = 5_000;
    private static final String[] HEADERS = {"ID", "구독ID", "정산월", "총액", "수수료", "순액"};

    private final SettlementRecordRepository repository;

    public Path export(String targetMonth) throws IOException {
        List<SettlementRecord> all = loadAll(targetMonth);

        Path outputPath = Files.createTempFile("settlement-legacy-" + targetMonth, ".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("정산내역");
            CellStyle headerStyle = createHeaderStyle(workbook);

            writeHeaderRow(sheet, headerStyle);

            int rowNum = 1;
            for (SettlementRecord record : all) {
                writeDataRow(sheet, rowNum++, record);
            }

            try (OutputStream out = Files.newOutputStream(outputPath)) {
                workbook.write(out);
            }
        }
        return outputPath;
    }

    /** [NEW-DESIGN] "전체를 한 번에 메모리에 올린다"는 Problem을 재현하기 위한 로더 —
     * 조회 자체는 페이지로 나누지만 결과를 하나의 List에 전부 누적한다. */
    private List<SettlementRecord> loadAll(String targetMonth) {
        List<SettlementRecord> all = new java.util.ArrayList<>();
        long lastSeenId = 0L;
        List<SettlementRecord> page;
        do {
            page = repository.findByTargetMonthAndSettlementStatusAndIdGreaterThanOrderByIdAsc(
                    targetMonth, SettlementRecord.Status.AGGREGATED, lastSeenId, PageRequest.of(0, FETCH_PAGE_SIZE));
            all.addAll(page);
            if (!page.isEmpty()) {
                lastSeenId = page.get(page.size() - 1).getId();
            }
        } while (!page.isEmpty());
        return all;
    }

    private CellStyle createHeaderStyle(XSSFWorkbook workbook) {
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
