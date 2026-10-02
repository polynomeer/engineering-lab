package com.portfolio.mdslab.messaging;

/**
 * [신규 결정] design/mds-global-distribution.md 4.1절 "등록 성공 시 ...
 * AlbumRegisteredEvent를 SQS로 발행한다"를 단순화한 이벤트 페이로드.
 * 실제 SQS 대신 자바 객체로 직접 전달한다(README 참고 — LocalStack/실제
 * SQS 없이 멱등 처리 메커니즘만 시연하기로 판단).
 *
 * title이 {@link #FORCE_FAIL_TITLE}이면 {@code AlbumRegistrationService}가
 * 의도적으로 예외를 던진다 — "처리 실패 후 같은 messageId로 재시도" 시나리오를
 * 결정론적으로 재현하기 위한 테스트 훅이다.
 */
public record AlbumRegisteredEvent(String messageId, String albumTitle) {

    public static final String FORCE_FAIL_TITLE = "__FORCE_FAIL__";
}
