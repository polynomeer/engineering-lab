package com.portfolio.equitylab.repository;

import com.portfolio.equitylab.registration.RowProcessor.MappedEquityRow;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** [NEW-DESIGN] 등록 파이프라인 시나리오 전용 배치 삽입 DAO. */
@Repository
public class EquityShareInsertDao {

    private static final String INSERT_SQL =
            "INSERT INTO equity_share (distributor_code, track_id, start_date, end_date, share_percentage) "
                    + "VALUES (?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public EquityShareInsertDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertBatch(List<MappedEquityRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                MappedEquityRow row = rows.get(i);
                ps.setString(1, row.distributorCode());
                ps.setLong(2, row.trackId());
                ps.setDate(3, Date.valueOf(row.startDate()));
                ps.setDate(4, Date.valueOf(row.endDate()));
                ps.setBigDecimal(5, row.sharePercentage());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }
}
