package com.portfolio.mcplab.contract.api.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * [FACT 기반 재현] 검증 파이프라인 1단계(Bean Validation) — facts
 * "2. 입력 구조 표준화" Decision("Map 기반 동적 입력을 DTO 기반 정적 타입으로 전환")과
 * design/mcp-platform-revamp.md 4장 요청 스키마를 이 랩 범위(Contract만)로 축소해
 * 그대로 구현했다. 여기 선언된 제약은 "문맥 없이 판단 가능한 형식 규칙"만 다룬다 —
 * 다른 필드와의 관계(예: 만료일이 시작일보다 빠른지)는 여기서 검증하지 않고
 * 3단계(도메인 규칙)로 넘긴다.
 */
public record ContractCreateRequest(
        @NotBlank @Size(max = 200) String counterpartyName,
        @NotNull LocalDate startsAt,
        @NotNull @Future LocalDate expiresAt
) {
}
