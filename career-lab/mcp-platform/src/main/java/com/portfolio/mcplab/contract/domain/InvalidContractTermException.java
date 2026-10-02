package com.portfolio.mcplab.contract.domain;

/**
 * [NEW-DESIGN] 도메인 규칙 위반 예외 — 검증 파이프라인 3단계(도메인 규칙 검증)에서만
 * 던져진다. Bean Validation 실패(400)와 구분되는 별도 예외 타입으로 두어, API 계층에서
 * 422 등으로 다르게 매핑할 수 있게 한다(design/mcp-platform-revamp.md 5.3절 참고).
 */
public class InvalidContractTermException extends RuntimeException {

    public InvalidContractTermException(String message) {
        super(message);
    }
}
