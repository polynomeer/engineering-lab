package com.portfolio.creatorlab.session;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * [FACT 기반 재현] "Before" — facts/projects/creator-studio.md 3번 섹션의
 * 확정된 문제를 그대로 재현한다: "Studio는 access token 원문을 Redis의 key로,
 * JWT를 파싱한 값 전체를 value로 저장해 세션처럼 사용했다."
 *
 * 식별된 문제(그대로 재현): "FE가 token을 refresh해 새 access token을 받아도,
 * 기존 access token의 만료 시각(exp)이 아직 남아 있고 Studio가 token rotation
 * 사실을 알 방법이 없어 옛 access token으로도 Redis 세션이 계속 통과될 수 있는
 * 빈틈이 있었다." — 이 클래스에는 "이전 access token의 세션을 찾아서 지운다"는
 * 기능 자체가 없다. sub+device 역인덱스가 없으므로 구조적으로 불가능하다.
 */
public class LegacySessionManager implements SessionManager {

    private static final String SESSION_PREFIX = "legacy:session:";
    private static final String REFRESH_PREFIX = "legacy:refresh:";

    private final StringRedisTemplate redis;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public LegacySessionManager(StringRedisTemplate redis, Duration accessTtl, Duration refreshTtl) {
        this.redis = redis;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    @Override
    public TokenPair issue(long memberId, String deviceId) {
        String accessToken = "at-" + UUID.randomUUID();
        String refreshToken = "rt-" + UUID.randomUUID();
        redis.opsForValue().set(SESSION_PREFIX + accessToken, new SessionData(memberId, deviceId).serialize(), accessTtl);
        redis.opsForValue().set(REFRESH_PREFIX + refreshToken, memberId + "|" + deviceId, refreshTtl);
        return new TokenPair(accessToken, refreshToken);
    }

    @Override
    public Optional<SessionData> lookup(String accessToken) {
        String raw = redis.opsForValue().get(SESSION_PREFIX + accessToken);
        return raw == null ? Optional.empty() : Optional.of(SessionData.deserialize(raw));
    }

    /**
     * BUG: 새 access token에 대한 세션은 새로 만들지만, refresh 이전에 발급됐던
     * access token의 세션 키({@code legacy:session:<oldAccessToken>})는 그대로
     * 남아있다 — Studio 입장에서는 "이 refresh token이 어떤 access token과 짝이었는지"를
     * 알 방법이 없으므로(원문 key 방식엔 역인덱스가 없다) 지울 대상 자체를 특정할 수
     * 없다. 결과적으로 exp가 남아있는 한 옛 access token은 계속 유효하다.
     */
    @Override
    public TokenPair refresh(String refreshToken, String deviceId) {
        String raw = redis.opsForValue().get(REFRESH_PREFIX + refreshToken);
        if (raw == null) {
            throw new InvalidTokenException("refresh token not found: " + refreshToken);
        }
        String[] parts = raw.split("\\|", 2);
        long memberId = Long.parseLong(parts[0]);
        return issue(memberId, deviceId); // 새 세션만 추가될 뿐, 이전 access token 세션은 손대지 않는다.
    }

    @Override
    public void revoke(String accessToken) {
        redis.delete(SESSION_PREFIX + accessToken);
    }
}
