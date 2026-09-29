package com.portfolio.mcplab.sequence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * [FACT 기반 재현] Before — facts/projects/mcp-platform-revamp.md
 * "4. 시퀀스 구조 개선 > 다중 인스턴스에서의 범위 충돌 가능성 > 기존 구현" 그대로:
 *
 * <blockquote>채번 로직을 SELECT 단계에서 제거하고, 시퀀스 테이블에서 한 번에 N건씩
 * 범위를 미리 확보(range allocation)해 INSERT 시점에 메모리에서 순서대로 소비하는
 * 방식으로 개선했다. 그러나 이 "범위를 확보하는 연산" 자체가 여러 서버 인스턴스에서
 * 동시에 실행될 수 있다는 것을 고려해 설계하지 않았다 — 인스턴스별로 범위를 구분해서
 * 안전하게 확보하는 장치가 없었다.</blockquote>
 *
 * <p>즉 "SELECT로 현재 값을 읽고 → 애플리케이션에서 range를 계산 → 별도 UPDATE로
 * 반영"하는 3단계가 하나의 원자적 연산으로 묶여 있지 않다. 두 인스턴스가 이 3단계
 * 사이에 끼어들면 같은 시작값을 기준으로 겹치는 구간을 각자 확보하게 된다
 * (facts "문제점" 항목의 재현).</p>
 */
@Component
public class NonAtomicContractCodeAllocator implements ContractCodeAllocator {

    /**
     * [NEW-DESIGN] SELECT 이후 UPDATE 이전에 넣는 인위적 지연.
     * design/mcp-platform-revamp.md 2.2절 "계약 코드 채번(동시 요청 시): 인위적 지연
     * 재현 후 측정"에 이미 명시된 재현 전략이다 — 실제 운영 환경에서도 이론상 발생
     * 가능한 경쟁 구간을, 테스트에서 타이밍에 기대지 않고 결정론적으로 넓혀 재현하기
     * 위한 것이다(순정 CLAUDE.md 지침의 "결정론적 재현" 우선 원칙). 픽스(After) 구현에는
     * 이 지연이 없다 — 원자적 단일 UPDATE라 애초에 경쟁 구간 자체가 존재하지 않는다.
     */
    private static final long RACE_WINDOW_DELAY_MS = 50;

    private final JdbcTemplate jdbcTemplate;

    public NonAtomicContractCodeAllocator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AllocatedRange allocateBlock(String seqKey, int blockSize) {
        // 1) SELECT로 현재 값을 읽는다 — 락 없음, 다른 인스턴스도 동시에 같은 값을 읽을 수 있다.
        long current = jdbcTemplate.queryForObject(
                "SELECT current_value FROM contract_sequence WHERE seq_key = ?",
                Long.class, seqKey);

        widenRaceWindow();

        // 2) 애플리케이션 메모리에서 range를 계산한다 — 이 시점의 current는 이미 stale할 수 있다.
        long newValue = current + blockSize;

        // 3) 별도 UPDATE로 반영한다 — 1)~3) 전체가 하나의 트랜잭션/락으로 보호되지 않는다.
        jdbcTemplate.update(
                "UPDATE contract_sequence SET current_value = ? WHERE seq_key = ?",
                newValue, seqKey);

        return new AllocatedRange(current + 1, newValue);
    }

    private void widenRaceWindow() {
        try {
            Thread.sleep(RACE_WINDOW_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
