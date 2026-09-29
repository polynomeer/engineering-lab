package com.portfolio.mcplab.contract.api;

import com.portfolio.mcplab.contract.api.dto.ContractCreateRequest;
import com.portfolio.mcplab.contract.api.dto.ContractResponse;
import com.portfolio.mcplab.contract.application.ContractService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * [NEW-DESIGN] api 계층 — design/mcp-platform-revamp.md 4장 "화면 단위가 아니라
 * 도메인 책임 단위로 엔드포인트를 나눈다"는 원칙에 따라 Contract 도메인 API로 둔다.
 *
 * <p>이 클래스는 오직 {@link ContractService}(application 계층)만 의존한다 —
 * {@code infrastructure} 패키지(Repository)를 직접 호출하지 않는다. 이 경계가
 * 지켜지는지가 ArchUnit 시나리오의 핵심 검증 대상이다
 * ({@code src/test/.../archrule} 패키지 참고, 위반 예시는
 * {@code src/test/.../badexample} 패키지에 별도로 둔다).</p>
 */
@RestController
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @PostMapping("/api/v1/contracts")
    @ResponseStatus(HttpStatus.CREATED)
    public ContractResponse create(@Valid @RequestBody ContractCreateRequest request) {
        String contractCode = contractService.createContract(
                request.counterpartyName(), request.startsAt(), request.expiresAt());
        return new ContractResponse(contractCode);
    }
}
