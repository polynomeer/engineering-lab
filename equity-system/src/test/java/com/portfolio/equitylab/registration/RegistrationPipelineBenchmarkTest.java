package com.portfolio.equitylab.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.equitylab.AbstractMySqlIntegrationTest;
import com.portfolio.equitylab.repository.EquityShareDeletionDao;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * facts/projects/equity-system.md 3번 항목("대량 등록 파이프라인 개선과
 * 병렬 처리 최적화")을 최소 단위로 재현한다.
 *
 * 행 1건당 검증 지연(RowProcessor.SIMULATED_VALIDATION_MILLIS=2ms)과
 * 데이터 건수(5,000건)는 [NEW-DESIGN] — 실제 40분/12분이라는 규모를 그대로
 * 재현하는 대신, "행 단위 대기 시간이 있는 작업을 병렬화하면 실제로
 * 빨라진다"는 동일한 메커니즘을 짧은 시간 안에 검증 가능한 규모로
 * 축소했다.
 */
@DisplayName("지분율 등록 파이프라인 — 순차 처리 vs ThreadPool 병렬 처리")
class RegistrationPipelineBenchmarkTest extends AbstractMySqlIntegrationTest {

    private static final int ROW_COUNT = 5_000;
    private static final int PARALLEL_CHUNK_SIZE = 250;

    @Autowired
    private EquityShareDeletionDao dao;
    @Autowired
    private SequentialRegistrationPipeline sequentialPipeline;
    @Autowired
    private ParallelRegistrationPipeline parallelPipeline;

    @Test
    @DisplayName("동일한 5,000건에서 ThreadPool 병렬 처리가 순차 처리보다 확실히 빠르고, 결과 건수는 동일하다")
    void parallelPipelineIsFasterThanSequentialWithIdenticalResult() {
        dao.truncate();
        List<RawEquityRow> rows = generateRawRows(ROW_COUNT);

        long sequentialStart = System.nanoTime();
        int sequentialInserted = sequentialPipeline.register(rows);
        long sequentialDurationMs = elapsedMs(sequentialStart);

        assertThat(sequentialInserted).isEqualTo(ROW_COUNT);
        assertThat(dao.countAll()).isEqualTo(ROW_COUNT);

        dao.truncate();

        long parallelStart = System.nanoTime();
        int parallelInserted = parallelPipeline.register(rows, PARALLEL_CHUNK_SIZE);
        long parallelDurationMs = elapsedMs(parallelStart);

        assertThat(parallelInserted).isEqualTo(ROW_COUNT);
        assertThat(dao.countAll()).isEqualTo(ROW_COUNT);

        System.out.printf(
                "%n[등록 파이프라인 벤치마크] sequential=%dms(단일 스레드) vs parallel=%dms"
                        + "(ThreadPool %d개+청크 %d건) — %.1f배 개선%n",
                sequentialDurationMs, parallelDurationMs, ParallelRegistrationPipeline.POOL_SIZE,
                PARALLEL_CHUNK_SIZE, sequentialDurationMs / (double) Math.max(parallelDurationMs, 1));

        assertThat(parallelDurationMs).isLessThan(sequentialDurationMs);
    }

    private static List<RawEquityRow> generateRawRows(int count) {
        List<RawEquityRow> rows = new ArrayList<>(count);
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        for (int i = 0; i < count; i++) {
            rows.add(new RawEquityRow("DIST-TEST", i + 1L, start, end, "0.50"));
        }
        return rows;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
