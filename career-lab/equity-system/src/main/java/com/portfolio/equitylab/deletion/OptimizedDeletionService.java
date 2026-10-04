package com.portfolio.equitylab.deletion;

import com.portfolio.equitylab.repository.EquityShareDeletionDao;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/**
 * [FACT 기반 재현] "After" — facts/projects/equity-system.md 1번 항목의
 * Decision(2026-09-27 확정)을 코드로 재현한다.
 *
 * 1. [FACT] 복합 인덱스(distributor_code, start_date, end_date) 활용
 * 2. [FACT] 전체 데이터의 상당 부분을 차지하는 대형 유통사는 유통사 코드를
 *    고정한 전용 경로(청크 단위 삭제)로 처리하고, 나머지 소규모 유통사는
 *    전용 경로 없이 일반 처리(단일 삭제)로 충분하다.
 * 3. [FACT] 삭제 작업을 단일 장기 트랜잭션이 아닌 청크 단위로 전환한다.
 *
 * 청크 삭제는 의도적으로 @Transactional로 감싸지 않는다 — JdbcTemplate이
 * 트랜잭션 컨텍스트 밖에서 각 update() 호출을 개별 auto-commit으로 실행하는
 * 것 자체가 "청크마다 독립된 트랜잭션"이라는 요구사항을 그대로 만족시킨다.
 *
 * "대형 유통사" 판단 기준(전체의 5% 이상)과 삭제 청크 크기(5,000건)는
 * [NEW-DESIGN] — 실제 FLO 값이 아니라 이 랩을 위해 새로 정한 값이다.
 */
@Service
public class OptimizedDeletionService {

    /** [NEW-DESIGN] 전체 데이터의 이 비율 이상을 차지하면 "대형 유통사"로 간주해 청크 경로를 탄다. */
    static final double LARGE_DISTRIBUTOR_THRESHOLD = 0.05;

    /** [NEW-DESIGN] 청크 1회당 삭제 건수. */
    static final int DELETE_CHUNK_SIZE = 5_000;

    private final EquityShareDeletionDao dao;

    public OptimizedDeletionService(EquityShareDeletionDao dao) {
        this.dao = dao;
    }

    public DeletionResult deleteAll(String distributorCode, LocalDate periodStart, LocalDate periodEnd) {
        boolean isLargeDistributor = isLargeDistributor(distributorCode);
        if (!isLargeDistributor) {
            long start = System.nanoTime();
            int deleted = dao.deleteOverlapping(distributorCode, periodStart, periodEnd);
            long durationMs = elapsedMs(start);
            return new DeletionResult(deleted, 1, false, durationMs);
        }
        return deleteInChunks(distributorCode, periodStart, periodEnd);
    }

    private boolean isLargeDistributor(String distributorCode) {
        long total = dao.countAll();
        if (total == 0) {
            return false;
        }
        long distributorTotal = dao.countByDistributor(distributorCode);
        return (double) distributorTotal / total >= LARGE_DISTRIBUTOR_THRESHOLD;
    }

    private DeletionResult deleteInChunks(String distributorCode, LocalDate periodStart, LocalDate periodEnd) {
        int totalDeleted = 0;
        int chunkCount = 0;
        int deletedInChunk;
        long maxChunkDurationMs = 0;
        do {
            long chunkStart = System.nanoTime();
            deletedInChunk = dao.deleteOverlappingChunk(distributorCode, periodStart, periodEnd, DELETE_CHUNK_SIZE);
            long chunkDurationMs = elapsedMs(chunkStart);
            maxChunkDurationMs = Math.max(maxChunkDurationMs, chunkDurationMs);

            totalDeleted += deletedInChunk;
            chunkCount++;
        } while (deletedInChunk == DELETE_CHUNK_SIZE);
        return new DeletionResult(totalDeleted, chunkCount, true, maxChunkDurationMs);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * maxSingleStepDurationMs — 가장 오래 걸린 "단일 삭제 단위"의 소요 시간이다.
     * 대형 유통사 경로에서는 가장 느렸던 청크 1회의 시간이고, 소규모 유통사
     * 경로에서는 단일 삭제 전체 시간이다. facts가 실제로 주장하는 개선은
     * "총 처리시간"이 아니라 "한 트랜잭션이 락을 쥐고 있는 시간"이므로,
     * 이 값을 legacy(단일 트랜잭션 전체 시간)와 비교하는 것이 올바른 검증이다
     * — 총 처리시간(청크 수 × 청크당 커밋 오버헤드)은 커밋마다 발생하는
     * fsync 비용 때문에 환경에 따라 legacy보다 더 걸릴 수도 있다(2026-09-28,
     * 이 랩에서 실제로 관찰됨 — README 참고).
     */
    public record DeletionResult(int deletedRows, int chunkCount, boolean tookDedicatedPath,
                                  long maxSingleStepDurationMs) {
    }
}
