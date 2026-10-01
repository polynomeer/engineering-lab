package com.portfolio.mcplab.contract.application;

import com.portfolio.mcplab.contract.domain.Contract;
import com.portfolio.mcplab.contract.infrastructure.ContractRepository;
import com.portfolio.mcplab.sequence.AllocatedRange;
import com.portfolio.mcplab.sequence.ContractCodeAllocator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Year;

/**
 * [NEW-DESIGN] application 계층 — 트랜잭션 경계는 이 메서드 하나
 * (design/mcp-platform-revamp.md 5.2절 ContractCommandService 패턴을 최소화한 버전).
 * api(Controller)는 이 서비스를 통해서만 도메인/저장소에 접근해야 한다 — ArchUnit
 * 규칙("api는 infrastructure에 직접 의존 금지")이 강제하는 경계가 바로 이 지점이다.
 */
@Service
public class ContractService {

    private final ContractCodeAllocator contractCodeAllocator;
    private final ContractRepository contractRepository;

    public ContractService(ContractCodeAllocator contractCodeAllocator, ContractRepository contractRepository) {
        this.contractCodeAllocator = contractCodeAllocator;
        this.contractRepository = contractRepository;
    }

    @Transactional
    public String createContract(String counterpartyName, LocalDate startsAt, LocalDate expiresAt) {
        AllocatedRange range = contractCodeAllocator.allocateBlock("CONTRACT_CODE", 1);
        // [NEW-DESIGN] 계약 코드 포맷: design/mcp-platform-revamp.md 8장 표에서 정한
        // "MCP-{연도}-{6자리 채번}" 포맷 그대로.
        String contractCode = "MCP-%d-%06d".formatted(Year.now().getValue(), range.rangeStart());

        Contract contract = Contract.create(contractCode, counterpartyName, startsAt, expiresAt);
        contractRepository.save(contract);
        return contract.contractCode();
    }
}
