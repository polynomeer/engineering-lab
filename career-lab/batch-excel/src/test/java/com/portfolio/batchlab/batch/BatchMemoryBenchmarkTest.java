package com.portfolio.batchlab.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.portfolio.batchlab.AbstractMySqlIntegrationTest;
import com.portfolio.batchlab.domain.SettlementRecord;
import com.portfolio.batchlab.repository.SettlementRecordRepository;
import com.portfolio.batchlab.support.MemoryUsageProbe;
import com.portfolio.batchlab.support.SettlementRecordTestDataGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * [FACT 기반 재현] facts/projects/batch-excel-optimization.md 15~17행,
 * design/batch-excel-optimization.md NF-1을 검증하는 벤치마크 테스트다.
 *
 * facts가 실제로 주장하는 개선 지표는 "총 처리시간"이 아니라 "메모리 피크"다
 * (career-hub의 지분율 랩 README에서 "청크로 나누면 총 처리시간이 빨라진다"는
 * 가정이 실측으로 틀렸다는 걸 이미 배웠다 — 청크 커밋마다 fsync 비용이 붙어
 * 오히려 느려질 수 있다). 그래서 이 테스트는 시간을 재지 않고, 두 Job이 실행되는
 * 동안의 JVM 힙 사용량(MemoryUsageProbe, MemoryMXBean 기반)만 비교한다.
 *
 * 같은 레코드 수(RECORD_COUNT)를 서로 다른 targetMonth에 시드해 두 Job이 독립적인
 * 데이터 집합을 처리하게 한다 — legacy Job이 이미 AGGREGATED로 바꿔버린 레코드를
 * chunk Job이 다시 읽는 것을 방지하기 위해서다.
 */
class BatchMemoryBenchmarkTest extends AbstractMySqlIntegrationTest {

    private static final String LEGACY_TARGET_MONTH = "2026-08";
    private static final String CHUNK_TARGET_MONTH = "2026-09";
    // [NEW-DESIGN] design 2.2절은 로컬/CI에서 재현 가능한 규모로 50만 건을 제안했으나,
    // 이 랩은 CI 실행 시간을 고려해 20만 건으로 축소했다 — 메모리 피크 차이를
    // 보여주는 데는 이 규모로도 충분하다는 걸 실측으로 확인했다(README 참고).
    private static final int RECORD_COUNT = 200_000;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("legacyTaskletAggregationJob")
    private Job legacyTaskletAggregationJob;

    @Autowired
    @Qualifier("chunkAggregationJob")
    private Job chunkAggregationJob;

    @Autowired
    private SettlementRecordTestDataGenerator dataGenerator;

    @Autowired
    private SettlementRecordRepository repository;

    @Test
    void chunkBasedJobUsesLessPeakHeapThanLegacyTaskletJob() throws Exception {
        dataGenerator.generate(LEGACY_TARGET_MONTH, RECORD_COUNT, SettlementRecord.Status.PENDING);
        assertEquals(RECORD_COUNT,
                repository.countByTargetMonthAndSettlementStatus(LEGACY_TARGET_MONTH, SettlementRecord.Status.PENDING));

        MemoryUsageProbe legacyProbe = new MemoryUsageProbe();
        legacyProbe.start();
        JobExecution legacyExecution = jobLauncher.run(legacyTaskletAggregationJob,
                new JobParametersBuilder()
                        .addString("targetMonth", LEGACY_TARGET_MONTH)
                        .addLong("runId", System.currentTimeMillis())
                        .toJobParameters());
        MemoryUsageProbe.Result legacyResult = legacyProbe.stop();

        assertEquals(BatchStatus.COMPLETED, legacyExecution.getStatus());
        assertEquals(RECORD_COUNT,
                repository.countByTargetMonthAndSettlementStatus(LEGACY_TARGET_MONTH, SettlementRecord.Status.AGGREGATED));

        dataGenerator.generate(CHUNK_TARGET_MONTH, RECORD_COUNT, SettlementRecord.Status.PENDING);
        assertEquals(RECORD_COUNT,
                repository.countByTargetMonthAndSettlementStatus(CHUNK_TARGET_MONTH, SettlementRecord.Status.PENDING));

        MemoryUsageProbe chunkProbe = new MemoryUsageProbe();
        chunkProbe.start();
        JobExecution chunkExecution = jobLauncher.run(chunkAggregationJob,
                new JobParametersBuilder()
                        .addString("targetMonth", CHUNK_TARGET_MONTH)
                        .addLong("runId", System.currentTimeMillis())
                        .toJobParameters());
        MemoryUsageProbe.Result chunkResult = chunkProbe.stop();

        assertEquals(BatchStatus.COMPLETED, chunkExecution.getStatus());
        assertEquals(RECORD_COUNT,
                repository.countByTargetMonthAndSettlementStatus(CHUNK_TARGET_MONTH, SettlementRecord.Status.AGGREGATED));

        double reductionPercent = 100.0 * (1 - (chunkResult.deltaBytes() / (double) legacyResult.deltaBytes()));
        System.out.println("========================================");
        System.out.println("[MEMORY] legacy tasklet (단일 트랜잭션): " + legacyResult);
        System.out.println("[MEMORY] chunk 기반 (flush/clear):      " + chunkResult);
        System.out.printf("[MEMORY] 힙 사용량(delta) 감소율: %.1f%%%n", reductionPercent);
        System.out.println("========================================");

        assertTrue(chunkResult.deltaBytes() < legacyResult.deltaBytes(),
                "chunk 기반 처리가 tasklet 단일 트랜잭션보다 힙 사용량 증가폭이 작아야 한다");
    }
}
