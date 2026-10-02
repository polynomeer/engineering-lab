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
 * [FACT 기반 재현] After(대안) — facts "올바른 개선방안" 1번
 * ({@code SELECT ... FOR UPDATE}로 같은 트랜잭션 안에서 계산·갱신)을 같은 동시 실행
 * 테스트로 검증한다. {@link AtomicUpdateAllocatorConcurrencyTest}와 동일한 기대치
 * (겹침 없음, 빈틈 없는 커버리지)를 만족해야 한다 — facts의 회고("SELECT FOR UPDATE도
 * 느리지 않았다")대로 이 방식 역시 다중 인스턴스에 안전한 대안임을 보여준다.
 */
class SelectForUpdateAllocatorConcurrencyTest extends AbstractMySqlIntegrationTest {

    private static final String SEQ_KEY = "CONTRACT_CODE";
    private static final int BLOCK_SIZE = 1000;
    private static final int THREAD_COUNT = 10;

    @Autowired
    private SelectForUpdateContractCodeAllocator allocator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSequence() {
        jdbcTemplate.update("UPDATE contract_sequence SET current_value = 0 WHERE seq_key = ?", SEQ_KEY);
    }

    @RepeatedTest(2)
    void concurrentAllocation_neverOverlaps_becauseRowLockSerializes() throws Exception {
        List<AllocatedRange> results = ConcurrentAllocationHarness.runConcurrently(
                allocator, SEQ_KEY, BLOCK_SIZE, THREAD_COUNT);

        assertThat(results).hasSize(THREAD_COUNT);

        List<String> overlaps = ConcurrentAllocationHarness.findOverlaps(results);
        assertThat(overlaps)
                .as("SELECT ... FOR UPDATE로 행 잠금을 쥐면 커밋 전까지 다른 트랜잭션이 대기하므로 겹치지 않는다")
                .isEmpty();

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
    }
}
