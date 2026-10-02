package com.portfolio.batchlab.support;

import com.portfolio.batchlab.domain.SettlementRecord;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [NEW-DESIGN] 벤치마크용 시드 데이터 생성기. equity-system-lab의
 * EquityShareTestDataGenerator와 동일한 목적 — JPA를 거치지 않고 JdbcTemplate
 * batchUpdate로 대량 INSERT해서 시드 데이터 생성 자체가 벤치마크 측정에
 * 영향을 주지 않게 한다.
 *
 * 지분율 랩에서 겪은 함정을 그대로 반영: MySQL Testcontainers JDBC URL에
 * rewriteBatchedStatements=true가 없으면 수만~수십만 건 INSERT가 개별
 * round-trip으로 나가 수십 분이 걸린다 — 이 값은 AbstractMySqlIntegrationTest의
 * 컨테이너 설정에서 보장한다.
 */
@Component
@RequiredArgsConstructor
public class SettlementRecordTestDataGenerator {

    private final DataSource dataSource;

    private static final String INSERT_SQL = """
            INSERT INTO settlement_record
                (subscription_id, target_month, gross_amount, fee_amount, net_amount, settlement_status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, NOW(6))
            """;

    @Transactional
    public void generate(String targetMonth, int count, SettlementRecord.Status status) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        int batchSize = 2000;
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int start = 0; start < count; start += batchSize) {
            int end = Math.min(start + batchSize, count);
            int batchLength = end - start;
            final int batchStart = start;

            jdbcTemplate.batchUpdate(INSERT_SQL, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                @Override
                public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                    long subscriptionId = (batchStart + i) % 50_000L + 1;
                    int gross = 5_000 + random.nextInt(45_000);
                    int fee = (int) Math.round(gross * 0.1);
                    ps.setLong(1, subscriptionId);
                    ps.setString(2, targetMonth);
                    ps.setInt(3, gross);
                    ps.setInt(4, fee);
                    ps.setInt(5, gross - fee);
                    ps.setString(6, status.name());
                }

                @Override
                public int getBatchSize() {
                    return batchLength;
                }
            });
        }
    }
}
