package com.portfolio.mcplab.contract.domain;

import java.time.LocalDate;

/**
 * [NEW-DESIGN] Contract Aggregate — 순수 도메인 객체(영속성 프레임워크에 묶이지 않음).
 * design/mcp-platform-revamp.md 5.2절 패턴을 최소 범위로 옮겼다. 이 랩의 핵심 검증
 * 대상(채번 동시성, ArchUnit)을 위한 최소한의 도메인만 둔다 — album/track/equity 전체
 * 모델은 범위 밖이다(README 참고).
 *
 * <p>도메인 규칙 하나만 강제한다: 계약 만료일은 시작일보다 앞설 수 없다. 이 검증은
 * Bean Validation으로는 표현할 수 없다(두 필드를 동시에 봐야 하는 문맥 의존 규칙) —
 * facts/projects/mcp-platform-revamp.md "2. 입력 구조 표준화" Decision이 말하는
 * "도메인 규칙은 Aggregate 내부에서 최종 검증"을 그대로 재현한다.</p>
 */
public class Contract {

    private final String contractCode;
    private final String counterpartyName;
    private final LocalDate startsAt;
    private final LocalDate expiresAt;

    private Contract(String contractCode, String counterpartyName, LocalDate startsAt, LocalDate expiresAt) {
        this.contractCode = contractCode;
        this.counterpartyName = counterpartyName;
        this.startsAt = startsAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 3단계 검증 파이프라인(Bean Validation → DTO 매핑 → 도메인 규칙 검증)의 마지막
     * 단계. Bean Validation은 API 계층에서 이미 통과한 값만 이 메서드에 도달한다고
     * 가정한다 — 여기서는 문맥(다른 필드와의 관계) 의존 규칙만 검증한다.
     */
    public static Contract create(String contractCode, String counterpartyName,
                                   LocalDate startsAt, LocalDate expiresAt) {
        if (expiresAt.isBefore(startsAt)) {
            throw new InvalidContractTermException(
                    "계약 만료일(%s)은 시작일(%s)보다 앞설 수 없음".formatted(expiresAt, startsAt));
        }
        return new Contract(contractCode, counterpartyName, startsAt, expiresAt);
    }

    public String contractCode() {
        return contractCode;
    }

    public String counterpartyName() {
        return counterpartyName;
    }

    public LocalDate startsAt() {
        return startsAt;
    }

    public LocalDate expiresAt() {
        return expiresAt;
    }
}
