package com.portfolio.mcplab.contract.infrastructure;

import com.portfolio.mcplab.contract.domain.Contract;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * [NEW-DESIGN] Contract 저장을 담당하는 infrastructure 계층 컴포넌트.
 * facts "3. 조회/변경 책임 분리" Decision(변경은 MyBatis 유지)을 그대로 따르지는
 * 않는다 — 이 랩은 채번 동시성·ArchUnit 증명이 핵심이라 영속성은 JdbcTemplate으로
 * 단순화했다(README에 명시). ArchUnit 레이어 규칙(application만 이 클래스에 접근
 * 가능, api는 접근 불가)을 검증하기 위한 최소 구현이다.
 */
@Repository
public class ContractRepository {

    private final JdbcTemplate jdbcTemplate;

    public ContractRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Contract contract) {
        jdbcTemplate.update(
                "INSERT INTO contract (contract_code, counterparty_name, starts_at, expires_at) VALUES (?, ?, ?, ?)",
                contract.contractCode(), contract.counterpartyName(), contract.startsAt(), contract.expiresAt());
    }
}
