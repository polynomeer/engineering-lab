package com.portfolio.mcplab.sequence;

import com.portfolio.mcplab.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [FACT 기반 재현] After — facts "올바른 개선방안" 2번
 * (원자적 단일 {@code UPDATE ... LAST_INSERT_ID})을 같은 동시 실행 테스트로 검증한다.
 * 같은 10개 스레드 x 1000개 범위 시나리오에서 겹치는 구간이 전혀 없어야 한다.
 *
 * <p>{@code @RepeatedTest(2)}로 최소 2회 연속 통과를 확인한다(CLAUDE.md 지침 —
 * flaky 체크).</p>
 */
class AtomicUpdateAllocatorConcurrencyTest extends AbstractMySqlIntegrationTest {

    private static final String SEQ_KEY = "CONTRACT_CODE";
    private static final int BLOCK_SIZE = 1000;
    private static final int THREAD_COUNT = 10;

    @Autowired
    private AtomicUpdateContractCodeAllocator allocator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSequence() {
        jdbcTemplate.update("UPDATE contract_sequence SET current_value = 0 WHERE seq_key = ?", SEQ_KEY);
    }

    @RepeatedTest(2)
    void concurrentAllocation_neverOverlaps_becauseUpdateIsAtomic() throws Exception {
        List<AllocatedRange> results = ConcurrentAllocationHarness.runConcurrently(
                allocator, SEQ_KEY, BLOCK_SIZE, THREAD_COUNT);

        assertThat(results).hasSize(THREAD_COUNT);

        List<String> overlaps = ConcurrentAllocationHarness.findOverlaps(results);
        assertThat(overlaps)
                .as("원자적 UPDATE...LAST_INSERT_ID는 인스턴스 수와 무관하게 겹치는 범위를 만들지 않는다")
                .isEmpty();

        // 겹침이 없을 뿐 아니라, 10개 구간을 정렬해서 이어붙이면 빈틈(gap) 없이
        // 1..THREAD_COUNT*BLOCK_SIZE를 정확히 채운다 — 직렬화가 완전함을 보여준다.
        List<AllocatedRange> sorted = results.stream()
                .sorted(Comparator.comparingLong(AllocatedRange::rangeStart))
                .toList();
        long expectedStart = 1;
        for (AllocatedRange range : sorted) {
            assertThat(range.rangeStart()).isEqualTo(expectedStart);
            assertThat(range.size()).isEqualTo(BLOCK_SIZE);
            expectedStart = range.rangeEnd() + 1;
        }
        assertThat(expectedStart - 1).isEqualTo((long) THREAD_COUNT * BLOCK_SIZE);

        long finalValue = jdbcTemplate.queryForObject(
                "SELECT current_value FROM contract_sequence WHERE seq_key = ?", Long.class, SEQ_KEY);
        assertThat(finalValue).isEqualTo((long) THREAD_COUNT * BLOCK_SIZE);
    }
}
