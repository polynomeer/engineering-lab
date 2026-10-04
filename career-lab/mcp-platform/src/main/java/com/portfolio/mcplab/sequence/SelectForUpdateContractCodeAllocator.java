package com.portfolio.mcplab.sequence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [FACT 기반 재현] After(대안) — facts/projects/mcp-platform-revamp.md
 * "4. 시퀀스 구조 개선 > 올바른 개선방안" 1번을 구현:
 *
 * <blockquote>시퀀스 테이블의 해당 행을 {@code SELECT ... FOR UPDATE}로 잠근 뒤 같은
 * 트랜잭션 안에서 범위를 계산하고 갱신·커밋한다 — 다른 인스턴스는 그 트랜잭션이 끝날
 * 때까지 같은 행의 잠금을 기다리게 되어 범위가 겹칠 수 없다.</blockquote>
 *
 * <p>같은 문서의 "회고 — 설계 전제의 정정"도 이 구현의 근거다: 당시에는
 * {@code SELECT ... FOR UPDATE}가 느릴 것이라 판단해 이를 피하는 방향으로 설계했지만,
 * 이후 직접 벤치마크해 보니 청크 단위로 묶은 {@code SELECT ... FOR UPDATE}도 느리지
 * 않았다는 정정이 있었다. 그래서 이 랩에서는 {@link AtomicUpdateContractCodeAllocator}
 * (원자적 단일 UPDATE)와 이 구현을 나란히 두고 둘 다 다중 인스턴스에 안전함을
 * 증명한다 — design/mcp-platform-revamp.md 7장이 정리한 대로, 최종 선택 기준은
 * 성능이 아니라 코드 단순성이었다는 점도 두 구현을 비교해보면 체감할 수 있다.</p>
 */
@Component
public class SelectForUpdateContractCodeAllocator implements ContractCodeAllocator {

    private final JdbcTemplate jdbcTemplate;

    public SelectForUpdateContractCodeAllocator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AllocatedRange allocateBlock(String seqKey, int blockSize) {
        // 행 잠금 획득 — 같은 seq_key를 잠그려는 다른 트랜잭션은 이 트랜잭션이 끝날 때까지 대기.
        long current = jdbcTemplate.queryForObject(
                "SELECT current_value FROM contract_sequence WHERE seq_key = ? FOR UPDATE",
                Long.class, seqKey);

        long newValue = current + blockSize;

        // 잠금을 쥔 채로 갱신 — 커밋(트랜잭션 종료) 시점까지 잠금이 유지되므로
        // "읽기+계산+쓰기" 전체가 사실상 원자적으로 처리된다.
        jdbcTemplate.update(
                "UPDATE contract_sequence SET current_value = ? WHERE seq_key = ?",
                newValue, seqKey);

        return new AllocatedRange(current + 1, newValue);
    }
}
