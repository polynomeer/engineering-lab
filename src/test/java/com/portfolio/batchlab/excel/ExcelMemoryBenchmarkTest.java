package com.portfolio.batchlab.excel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.portfolio.batchlab.AbstractMySqlIntegrationTest;
import com.portfolio.batchlab.domain.SettlementRecord;
import com.portfolio.batchlab.support.MemoryUsageProbe;
import com.portfolio.batchlab.support.SettlementRecordTestDataGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * [FACT 기반 재현] facts/projects/batch-excel-optimization.md 53~55행,
 * design/batch-excel-optimization.md NF-3을 검증하는 벤치마크 테스트다.
 *
 * XSSFWorkbook(전량 인메모리) vs SXSSFWorkbook(스트리밍, window 500행)으로
 * 같은 규모의 데이터를 Excel로 생성할 때 힙 사용량이 얼마나 차이 나는지
 * MemoryUsageProbe로 직접 측정한다.
 */
class ExcelMemoryBenchmarkTest extends AbstractMySqlIntegrationTest {

    private static final String TARGET_MONTH = "2026-08";
    // [NEW-DESIGN] Excel 생성 시나리오는 배치 시나리오보다 셀 객체 수가 많아(행당 6컬럼)
    // 10만 건 규모로도 XSSFWorkbook의 전량 적재 문제가 뚜렷하게 재현된다는 걸 확인했다.
    private static final int RECORD_COUNT = 100_000;

    @Autowired
    private SettlementRecordTestDataGenerator dataGenerator;

    @Autowired
    private LegacyExcelExportService legacyExcelExportService;

    @Autowired
    private StreamingExcelExportService streamingExcelExportService;

    private Path legacyFile;
    private Path streamingFile;

    @AfterEach
    void cleanUp() throws Exception {
        if (legacyFile != null) {
            Files.deleteIfExists(legacyFile);
        }
        if (streamingFile != null) {
            Files.deleteIfExists(streamingFile);
        }
    }

    @Test
    void streamingExportUsesLessPeakHeapThanFullyInMemoryExport() throws Exception {
        dataGenerator.generate(TARGET_MONTH, RECORD_COUNT, SettlementRecord.Status.AGGREGATED);

        MemoryUsageProbe legacyProbe = new MemoryUsageProbe();
        legacyProbe.start();
        legacyFile = legacyExcelExportService.export(TARGET_MONTH);
        MemoryUsageProbe.Result legacyResult = legacyProbe.stop();

        assertTrue(Files.size(legacyFile) > 0, "legacy XSSF 파일이 생성되어야 한다");

        MemoryUsageProbe streamingProbe = new MemoryUsageProbe();
        streamingProbe.start();
        streamingFile = streamingExcelExportService.export(TARGET_MONTH);
        MemoryUsageProbe.Result streamingResult = streamingProbe.stop();

        assertTrue(Files.size(streamingFile) > 0, "streaming SXSSF 파일이 생성되어야 한다");

        double reductionPercent = 100.0 * (1 - (streamingResult.deltaBytes() / (double) legacyResult.deltaBytes()));
        System.out.println("========================================");
        System.out.println("[MEMORY] XSSFWorkbook (전량 적재):  " + legacyResult);
        System.out.println("[MEMORY] SXSSFWorkbook (스트리밍):  " + streamingResult);
        System.out.printf("[MEMORY] 힙 사용량(delta) 감소율: %.1f%%%n", reductionPercent);
        System.out.println("========================================");

        assertTrue(streamingResult.deltaBytes() < legacyResult.deltaBytes(),
                "SXSSF 스트리밍 방식이 XSSFWorkbook 전량 적재보다 힙 사용량 증가폭이 작아야 한다");
    }
}
