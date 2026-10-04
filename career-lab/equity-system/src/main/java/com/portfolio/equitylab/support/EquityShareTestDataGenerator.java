package com.portfolio.equitylab.support;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * [NEW-DESIGN] 벤치마크용 시드 데이터 생성기. 실제 FLO 데이터가 아니라, 이
 * 랩에서 "협회·일부 대형 유통사가 80% 이상을 차지하는 왜곡된 분포"
 * (facts/projects/equity-system.md, 2026-09-27 확인)를 재현하기 위해
 * 합성한 데이터다. JPA 엔티티 저장 대신 JdbcTemplate 배치 삽입을 쓰는 이유는
 * 수십만~백만 건 규모를 영속성 컨텍스트 없이 빠르게 넣기 위해서다.
 */
public class EquityShareTestDataGenerator {

    private static final int BATCH_SIZE = 2_000;

    private final JdbcTemplate jdbcTemplate;

    public EquityShareTestDataGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** distributorCode의 rowCount건을 전부 [periodStart, periodEnd] 하나의 기간에 채워 넣는다. */
    public void seedSinglePeriod(String distributorCode, int rowCount, LocalDate periodStart, LocalDate periodEnd) {
        seed(distributorCode, rowCount, i -> periodStart, i -> periodEnd);
    }

    /** distributorCode의 rowCount건을 매달 겹치지 않는 기간으로 흩어 넣는다(소규모 유통사용). */
    public void seedSpreadAcrossMonths(String distributorCode, int rowCount, LocalDate firstMonthStart) {
        seed(distributorCode, rowCount,
                i -> firstMonthStart.plusMonths(i % 24),
                i -> firstMonthStart.plusMonths(i % 24).plusDays(27));
    }

    private void seed(String distributorCode, int rowCount,
                       java.util.function.IntFunction<LocalDate> startDateFn,
                       java.util.function.IntFunction<LocalDate> endDateFn) {
        String sql = "INSERT INTO equity_share "
                + "(distributor_code, track_id, start_date, end_date, share_percentage) "
                + "VALUES (?, ?, ?, ?, ?)";

        int inserted = 0;
        while (inserted < rowCount) {
            int thisBatchSize = Math.min(BATCH_SIZE, rowCount - inserted);
            List<Integer> indices = java.util.stream.IntStream.range(inserted, inserted + thisBatchSize)
                    .boxed()
                    .toList();

            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    int rowIndex = indices.get(i);
                    ps.setString(1, distributorCode);
                    ps.setLong(2, rowIndex + 1L);
                    ps.setDate(3, Date.valueOf(startDateFn.apply(rowIndex)));
                    ps.setDate(4, Date.valueOf(endDateFn.apply(rowIndex)));
                    ps.setBigDecimal(5, java.math.BigDecimal.valueOf(0.5));
                }

                @Override
                public int getBatchSize() {
                    return thisBatchSize;
                }
            });
            inserted += thisBatchSize;
        }
    }
}
