package com.portfolio.equitylab.deletion;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.equitylab.AbstractMySqlIntegrationTest;
import com.portfolio.equitylab.repository.EquityShareDeletionDao;
import com.portfolio.equitylab.support.EquityShareTestDataGenerator;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * facts/projects/equity-system.md 1번 항목("대량 삭제 성능 개선과 I/O 병목
 * 제거")을 최소 단위로 재현한다.
 *
 * Before(LegacyDeletionService, 인덱스 없음, 단일 삭제) vs
 * After(OptimizedDeletionService, 복합 인덱스 + 대형 유통사 청크 경로)를
 * 동일한 데이터로 재현해 시간을 비교한다.
 *
 * 시드 데이터 분포(총 60만 건 중 KOMCA 30만/BIGCO 25만이 91.7%)는
 * [NEW-DESIGN] — facts에서 확인된 "협회+대형 유통사가 80% 이상"이라는
 * 사실(2026-09-27)을 재현하기 위해 이 랩에서 새로 정한 합성 수치다.
 */
@DisplayName("지분율 대량 삭제 — 인덱스/청크 적용 전후 비교")
class DeletionPerformanceBenchmarkTest extends AbstractMySqlIntegrationTest {

    private static final LocalDate TARGET_PERIOD_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate TARGET_PERIOD_END = LocalDate.of(2026, 1, 31);
    private static final int KOMCA_ROW_COUNT = 300_000;
    private static final int BIGCO_ROW_COUNT = 250_000;
    private static final int SMALL_DISTRIBUTOR_COUNT = 8;
    private static final int SMALL_DISTRIBUTOR_ROW_COUNT = 6_250; // 8 * 6,250 = 50,000

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EquityShareDeletionDao dao;
    @Autowired
    private LegacyDeletionService legacyDeletionService;
    @Autowired
    private OptimizedDeletionService optimizedDeletionService;

    @Test
    @DisplayName("optimized의 최대 단일 트랜잭션 점유 시간이 legacy의 전체 트랜잭션 시간보다 훨씬 짧다 (락 점유 시간 감소 증명)")
    void optimizedDeletionHoldsLocksForMuchShorterTimeThanLegacy() {
        seedBenchmarkData();
        long totalBeforeLegacy = dao.countAll();
        assertThat(totalBeforeLegacy)
                .isEqualTo(KOMCA_ROW_COUNT + BIGCO_ROW_COUNT
                        + (long) SMALL_DISTRIBUTOR_COUNT * SMALL_DISTRIBUTOR_ROW_COUNT);

        // ── Before: 인덱스 없이 단일 삭제 ──────────────────────────────
        long legacyStart = System.nanoTime();
        int legacyDeleted = legacyDeletionService.deleteAll("KOMCA", TARGET_PERIOD_START, TARGET_PERIOD_END);
        long legacyDurationMs = elapsedMs(legacyStart);

        assertThat(legacyDeleted).isEqualTo(KOMCA_ROW_COUNT);
        assertThat(dao.countByDistributor("KOMCA")).isZero();

        // ── 동일 조건 재현을 위해 데이터를 리셋하고 다시 시드 ──────────
        dao.truncate();
        seedBenchmarkData();
        dao.addPerformanceIndex();

        // ── After: 복합 인덱스 + 대형 유통사 청크 경로 ─────────────────
        long optimizedStart = System.nanoTime();
        OptimizedDeletionService.DeletionResult result =
                optimizedDeletionService.deleteAll("KOMCA", TARGET_PERIOD_START, TARGET_PERIOD_END);
        long optimizedDurationMs = elapsedMs(optimizedStart);

        assertThat(result.deletedRows()).isEqualTo(KOMCA_ROW_COUNT);
        assertThat(result.tookDedicatedPath()).isTrue();
        assertThat(result.chunkCount()).isGreaterThan(1);
        assertThat(dao.countByDistributor("KOMCA")).isZero();

        System.out.printf(
                "%n[대량 삭제 벤치마크] legacy=%dms(단일 트랜잭션, 락 %dms 점유) vs "
                        + "optimized=총 %dms/청크 %d회(청크 1회당 최대 %dms만 락 점유) "
                        + "— 최대 락 점유 시간 %.1f배 감소%n",
                legacyDurationMs, legacyDurationMs, optimizedDurationMs, result.chunkCount(),
                result.maxSingleStepDurationMs(),
                legacyDurationMs / (double) Math.max(result.maxSingleStepDurationMs(), 1));

        // facts가 실제로 주장하는 개선은 "총 처리시간"이 아니라 "트랜잭션 하나가
        // 락을 쥐고 있는 시간"이다(equity-system.md 1번 항목 Impact: "락 점유
        // 시간 감소로 동시 작업 안정성"). 총 처리시간은 청크마다 붙는 커밋
        // 오버헤드(fsync) 때문에 오히려 legacy보다 길게 나올 수 있다는 것을
        // 이 랩에서 실제로 확인했다(design/equity-system.md, README 참고) —
        // 그래서 검증 대상은 "가장 오래 걸린 단일 삭제 단위(청크)의 시간"이며,
        // 이게 legacy의 전체 트랜잭션 시간보다 훨씬 짧아야 한다.
        assertThat(result.maxSingleStepDurationMs()).isLessThan(legacyDurationMs);
    }

    private void seedBenchmarkData() {
        EquityShareTestDataGenerator generator = new EquityShareTestDataGenerator(jdbcTemplate);
        generator.seedSinglePeriod("KOMCA", KOMCA_ROW_COUNT, TARGET_PERIOD_START, TARGET_PERIOD_END);
        generator.seedSpreadAcrossMonths("BIGCO", BIGCO_ROW_COUNT, LocalDate.of(2024, 1, 1));
        for (int i = 1; i <= SMALL_DISTRIBUTOR_COUNT; i++) {
            generator.seedSpreadAcrossMonths("DIST-SMALL-" + i, SMALL_DISTRIBUTOR_ROW_COUNT,
                    LocalDate.of(2024, 1, 1));
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
