package com.portfolio.creatorlab.session;

import java.util.Optional;

/**
 * facts/projects/creator-studio.md 3번 섹션 / design/creator-studio.md 5.4절.
 *
 * {@link LegacySessionManager}는 원본의 "access token 원문을 Redis key로 쓰는" 구조
 * ([FACT 기반 재현] — 식별된 문제 그대로)를, {@link ImprovedSessionManager}는
 * facts에 "설계만 하고 실제 구현·배포는 하지 않았다"고 명시된 해시 key + 역인덱스
 * 개선안을 이번에 실제로 구현한 것([FACT 기반 재현] — 개선안은 설계까지 확정된
 * 사실이고, 이 랩이 그 설계를 처음으로 코드화한다)이다.
 */
public interface SessionManager {

    TokenPair issue(long memberId, String deviceId);

    Optional<SessionData> lookup(String accessToken);

    /** refresh 성공 시 이전 access token은 이후 즉시(=exp를 기다리지 않고) lookup에 실패해야 한다. */
    TokenPair refresh(String refreshToken, String deviceId);

    void revoke(String accessToken);
}
