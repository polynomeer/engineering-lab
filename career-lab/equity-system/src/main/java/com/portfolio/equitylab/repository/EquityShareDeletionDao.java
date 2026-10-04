package com.portfolio.equitylab.repository;

import java.sql.Date;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * [NEW-DESIGN] 삭제 시나리오 전용 raw SQL DAO. JPQL은 DELETE에 LIMIT/ORDER BY를
 * 지원하지 않아 청크 삭제를 표현할 수 없으므로 JdbcTemplate을 직접 쓴다.
 *
 * 두 쿼리(legacy/optimized)는 겹침 조건 자체는 동일한(AND 기반, 수학적으로
 * 올바른) 조건을 쓴다 — facts/projects/equity-system.md에 남아있는 "OR 기반
 * 조건" 예시 SQL은 그 문서에도 "실제 쿼리 아님, 일러스트레이션"이라고
 * 명시돼 있어 그대로 재현하지 않았다. 이 랩이 실제로 재현·증명하려는 변수는
 * "복합 인덱스 유무"와 "청크 단위 삭제 여부" 두 가지로 한정했다
 * (2026-09-28, 재구현 설계 시 새로 내린 결정).
 */
@Repository
public class EquityShareDeletionDao {

    private static final String OVERLAP_CONDITION =
            "distributor_code = ? AND start_date <= ? AND end_date >= ?";

    private final JdbcTemplate jdbcTemplate;

    public EquityShareDeletionDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 대상 조건에 맞는 모든 row를 한 번에 삭제한다 (단일 트랜잭션, 청크 없음). */
    public int deleteOverlapping(String distributorCode, LocalDate periodStart, LocalDate periodEnd) {
        return jdbcTemplate.update(
                "DELETE FROM equity_share WHERE " + OVERLAP_CONDITION,
                distributorCode, Date.valueOf(periodEnd), Date.valueOf(periodStart));
    }

    /** 청크 한 번만 삭제하고 영향받은 row 수를 반환한다 — 호출부가 0이 될 때까지 반복 호출한다. */
    public int deleteOverlappingChunk(String distributorCode, LocalDate periodStart, LocalDate periodEnd,
                                       int chunkSize) {
        return jdbcTemplate.update(
                "DELETE FROM equity_share WHERE " + OVERLAP_CONDITION + " ORDER BY start_date LIMIT ?",
                distributorCode, Date.valueOf(periodEnd), Date.valueOf(periodStart), chunkSize);
    }

    public long countByDistributor(String distributorCode) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM equity_share WHERE distributor_code = ?", Long.class, distributorCode);
        return count == null ? 0L : count;
    }

    public long countAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM equity_share", Long.class);
        return count == null ? 0L : count;
    }

    /** [NEW-DESIGN] "2. 대형 유통사 전용 경로" 개선의 핵심 — 복합 인덱스를 추가한다. */
    public void addPerformanceIndex() {
        jdbcTemplate.execute(
                "CREATE INDEX idx_distributor_period ON equity_share (distributor_code, start_date, end_date)");
    }

    public void dropPerformanceIndexIfExists() {
        jdbcTemplate.execute("DROP INDEX idx_distributor_period ON equity_share");
    }

    public void truncate() {
        jdbcTemplate.execute("TRUNCATE TABLE equity_share");
    }
}
