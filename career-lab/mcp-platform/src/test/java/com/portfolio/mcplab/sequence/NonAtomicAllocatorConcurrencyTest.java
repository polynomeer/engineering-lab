package com.portfolio.mcplab.sequence;

import com.portfolio.mcplab.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [FACT 기반 재현] Before — facts "다중 인스턴스에서의 범위 충돌 가능성" 문제를
 * 실제 동시 요청으로 재현한다. 10개 스레드가 동시에 "1000개씩 범위 확보"를 호출한 뒤,
 * 모든 스레드가 받은 범위를 합쳤을 때 겹치는 구간이 있는지 검사한다(과제 지시 시나리오
 * 그대로).
 *
 * <p>{@link NonAtomicContractCodeAllocator}는 SELECT 이후 UPDATE 이전에 50ms의 인위적
 * 지연을 둔다(design/mcp-platform-revamp.md 2.2절에 이미 명시된 재현 전략) — 10개
 * 스레드가 동시에 출발하면 전부 지연 구간 안에서 같은 current_value를 읽게 되므로,
 * 매 실행마다 결정론적으로 겹침이 재현된다(타이밍 운에 기대지 않음).</p>
 */
class NonAtomicAllocatorConcurrencyTest extends AbstractMySqlIntegrationTest {

    private static final String SEQ_KEY = "CONTRACT_CODE";
    private static final int BLOCK_SIZE = 1000;
    private static final int THREAD_COUNT = 10;

    @Autowired
    private NonAtomicContractCodeAllocator allocator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSequence() {
        jdbcTemplate.update("UPDATE contract_sequence SET current_value = 0 WHERE seq_key = ?", SEQ_KEY);
    }

    @Test
    void concurrentAllocation_producesOverlappingRanges_becauseReadComputeWriteIsNotAtomic() throws Exception {
        List<AllocatedRange> results = ConcurrentAllocationHarness.runConcurrently(
                allocator, SEQ_KEY, BLOCK_SIZE, THREAD_COUNT);

        assertThat(results).hasSize(THREAD_COUNT);

        List<String> overlaps = ConcurrentAllocationHarness.findOverlaps(results);

        // facts "다중 인스턴스에서의 범위 충돌 가능성"이 실제로 재현됨을 증명한다.
        // 10개 스레드가 모두 SELECT를 지연 구간 안에서 먼저 수행하므로, 서로 겹치는
        // (사실상 거의 동일한) 범위를 받는다 — lost update.
        assertThat(overlaps)
                .as("read-compute-write가 원자적이지 않으면 여러 인스턴스가 겹치는 범위를 확보한다")
                .isNotEmpty();

        // 부수 증거: lost update 때문에 최종 current_value는 THREAD_COUNT * BLOCK_SIZE에
        // 훨씬 못 미친다(모든 스레드가 같은 시작값을 읽고 마지막에 쓴 값만 남으므로).
        long finalValue = jdbcTemplate.queryForObject(
                "SELECT current_value FROM contract_sequence WHERE seq_key = ?", Long.class, SEQ_KEY);
        assertThat(finalValue)
                .as("lost update로 인해 최종 시퀀스 값이 기대치(%d)에 미달한다", (long) THREAD_COUNT * BLOCK_SIZE)
                .isLessThan((long) THREAD_COUNT * BLOCK_SIZE);
    }
}
