package com.portfolio.creatorlab.integration;

/** [NEW-DESIGN] 외부 연동 클라이언트 호출 실패를 나타내는 공통 예외 — {@code @Retryable} 대상. */
public class ExternalIntegrationException extends RuntimeException {
    public ExternalIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ExternalIntegrationException(String message) {
        super(message);
    }
}
