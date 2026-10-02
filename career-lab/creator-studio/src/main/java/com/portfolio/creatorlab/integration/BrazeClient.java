package com.portfolio.creatorlab.integration;

/**
 * [NEW-DESIGN] design NFR-6 — 외부 연동 클라이언트는 인터페이스로 추상화해 테스트
 * 더블로 대체 가능해야 한다. 실제 Braze API 스펙과는 무관한, 이 랩 전용 최소 인터페이스.
 */
public interface BrazeClient {
    void track(ContentChangedEvent event);
}
