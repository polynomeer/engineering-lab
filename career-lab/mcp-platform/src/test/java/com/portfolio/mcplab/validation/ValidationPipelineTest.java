package com.portfolio.mcplab.validation;

import com.portfolio.mcplab.contract.api.dto.ContractCreateRequest;
import com.portfolio.mcplab.contract.domain.Contract;
import com.portfolio.mcplab.contract.domain.InvalidContractTermException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [FACT 기반 재현] 시나리오 3 (선택) — facts "2. 입력 구조 표준화와 검증 파이프라인
 * 재설계" Decision: "검증 흐름을 Bean Validation → DTO 매핑 → 도메인 규칙 검증
 * 단계로 분리". DB나 Spring 컨텍스트 없이도 파이프라인의 세 단계를 각각 독립적으로
 * 증명할 수 있음을 보여주는 순수 단위 테스트다.
 *
 * <p>이 랩에서는 MapStruct 대신 {@link com.portfolio.mcplab.contract.application.ContractService}의
 * 수동 매핑으로 2단계를 단순화했다(README 참고) — 파이프라인의 "단계 분리" 자체가
 * 검증 대상이지 MapStruct 채택 여부가 아니기 때문이다.</p>
 */
class ValidationPipelineTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        validatorFactory.close();
    }

    @Test
    void stage1_beanValidation_rejectsBlankCounterpartyName() {
        // 형식 규칙(문맥 없이 판단 가능) 위반 — Bean Validation 단계에서 걸려야 한다.
        ContractCreateRequest request = new ContractCreateRequest(
                "", LocalDate.now(), LocalDate.now().plusYears(1));

        Set<ConstraintViolation<ContractCreateRequest>> violations = validator.validate(request);

        assertThat(violations)
                .as("counterpartyName이 공백이면 Bean Validation(@NotBlank)에서 걸려야 한다")
                .isNotEmpty();
    }

    @Test
    void stage1_beanValidation_rejectsPastExpiresAt() {
        // @Future 위반 — 역시 문맥 없이 판단 가능한 형식 규칙.
        ContractCreateRequest request = new ContractCreateRequest(
                "㈜예시레이블", LocalDate.now(), LocalDate.now().minusDays(1));

        Set<ConstraintViolation<ContractCreateRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void stage1_beanValidation_passesForWellFormedRequest() {
        ContractCreateRequest request = new ContractCreateRequest(
                "㈜예시레이블", LocalDate.now(), LocalDate.now().plusYears(1));

        Set<ConstraintViolation<ContractCreateRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void stage3_domainRule_rejectsExpiresBeforeStarts_evenThoughBeanValidationPasses() {
        // Bean Validation만 보면 두 필드 다 "형식상" 멀쩡하다(둘 다 @NotNull, expiresAt은
        // 미래 날짜) — 하지만 "만료일이 시작일보다 앞선다"는 문맥 의존 규칙은 도메인
        // 계층(Aggregate)에서만 판단할 수 있다. facts 2번 항목 Decision의 핵심.
        LocalDate startsAt = LocalDate.now().plusMonths(6);
        LocalDate expiresAt = LocalDate.now().plusMonths(1); // startsAt보다 이전

        ContractCreateRequest request = new ContractCreateRequest("㈜예시레이블", startsAt, expiresAt);
        assertThat(validator.validate(request))
                .as("Bean Validation 단계만으로는 이 요청이 통과해버린다 — 도메인 규칙이 따로 필요한 이유")
                .isEmpty();

        InvalidContractTermException exception = assertThrows(InvalidContractTermException.class,
                () -> Contract.create("MCP-2026-000001", request.counterpartyName(),
                        request.startsAt(), request.expiresAt()));

        assertThat(exception.getMessage()).contains("앞설 수 없음");
    }

    @Test
    void stage3_domainRule_acceptsConsistentDates() {
        LocalDate startsAt = LocalDate.now();
        LocalDate expiresAt = LocalDate.now().plusYears(1);

        Contract contract = Contract.create("MCP-2026-000002", "㈜예시레이블", startsAt, expiresAt);

        assertThat(contract.contractCode()).isEqualTo("MCP-2026-000002");
    }
}
