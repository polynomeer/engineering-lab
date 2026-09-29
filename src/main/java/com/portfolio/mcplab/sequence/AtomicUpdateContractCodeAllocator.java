package com.portfolio.mcplab.sequence;

import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [FACT 기반 재현] After — facts/projects/mcp-platform-revamp.md
 * "4. 시퀀스 구조 개선 > 올바른 개선방안" 2번을 그대로 구현:
 *
 * <blockquote>{@code UPDATE ... SET value = LAST_INSERT_ID(value + N) WHERE id = ...}
 * 처럼 "읽기+계산+쓰기"를 하나의 원자적 UPDATE 문으로 묶는다 — MySQL/InnoDB에서는 이
 * UPDATE 자체가 해당 행에 대해 원자적으로 실행되므로, 여러 인스턴스가 동시에 호출해도
 * 각 UPDATE가 순차적으로 직렬화되어 서로 겹치지 않는 범위를 받는다.</blockquote>
 *
 * <p>design/mcp-platform-revamp.md 6-(b), 8장의 SQL을 그대로 사용한다. 채번 로직을
 * 메인 비즈니스 트랜잭션과 분리한다는 facts Decision("시퀀스 증가 로직을 별도
 * 트랜잭션으로 분리")을 {@code REQUIRES_NEW}로 구체화했다(design 8장 표 마지막 행,
 * 🆕).</p>
 *
 * <p>{@code LAST_INSERT_ID()}는 커넥션(세션) 단위 상태이므로, UPDATE와 그 직후의
 * {@code SELECT LAST_INSERT_ID()}가 반드시 같은 커넥션에서 실행돼야 한다. 이 메서드를
 * {@code @Transactional}로 감싸면 Spring이 트랜잭션 동기화를 통해 같은
 * {@link JdbcTemplate} 호출들이 동일 커넥션을 사용하도록 보장한다.</p>
 *
 * <p>[NEW-DESIGN] 세 구현체 모두 빈으로 등록해 각자의 동시성 테스트에서 직접
 * 주입받아 비교할 수 있게 하되, {@link ContractService} 등 실제 애플리케이션
 * 배선에서 모호하지 않도록 이 구현체를 {@link Primary}로 지정한다 —
 * design/mcp-platform-revamp.md 7장이 정리한 최종 선택(성능이 아니라 코드
 * 단순성 때문에 원자적 단일 UPDATE를 택함)을 그대로 반영한 것이다.</p>
 */
@Primary
@Component
public class AtomicUpdateContractCodeAllocator implements ContractCodeAllocator {

    private final JdbcTemplate jdbcTemplate;

    public AtomicUpdateContractCodeAllocator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AllocatedRange allocateBlock(String seqKey, int blockSize) {
        // 하나의 UPDATE 문 안에서 "다음 값 계산"과 "반영"이 원자적으로 일어난다.
        // InnoDB는 이 UPDATE가 잡는 행 잠금을 커밋 시점까지 유지하므로, 동시에 같은
        // seq_key를 UPDATE하려는 다른 트랜잭션은 이 트랜잭션이 끝날 때까지 대기한다
        // (=자동으로 직렬화, 별도의 애플리케이션 락이 필요 없다).
        jdbcTemplate.update(
                "UPDATE contract_sequence SET current_value = LAST_INSERT_ID(current_value + ?) WHERE seq_key = ?",
                blockSize, seqKey);

        // 같은 커넥션에서 방금 확보한 블록의 "끝값"을 읽는다.
        long blockEnd = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        long blockStart = blockEnd - blockSize + 1;

        return new AllocatedRange(blockStart, blockEnd);
    }
}
