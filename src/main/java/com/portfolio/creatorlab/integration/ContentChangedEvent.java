package com.portfolio.creatorlab.integration;

/**
 * [FACT 기반 재현] facts/projects/creator-studio.md 1번 섹션 — "외부 연동을 도메인
 * 트랜잭션의 부수 효과로 정의"한 확정된 결정. 도메인 서비스는 이 이벤트를 발행할 뿐,
 * Braze/Mixpanel을 직접 호출하지 않는다.
 */
public record ContentChangedEvent(ContentType contentType, Long contentId, ChangeType changeType) {
}
