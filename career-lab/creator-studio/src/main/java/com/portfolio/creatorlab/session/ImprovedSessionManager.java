package com.portfolio.creatorlab.session;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * [FACT 기반 재현] "After" — facts/projects/creator-studio.md 3번 섹션의
 * 개선안을 그대로 구현한다: "access token 원문 대신 sha256(access_token) 해시를
 * Redis key로 쓰고, sub+device_id 조합을 key로 하는 '현재 유효한 token 해시'
 * 역인덱스를 별도로 둬서, 새 token이 한 번 쓰이면 이전 token의 세션을 즉시
 * 삭제하고 이후 요청은 해시 불일치로 거절하는 방향." facts는 "문제를 식별하고
 * 개선안을 제안하는 데까지만 진행했고, 실제 구현·배포는 하지 않았다"고 명시한다 —
 * 이 클래스가 그 미구현 설계를 처음으로 코드화한 것이다.
 *
 * 키 구조는 design/creator-studio.md 3.4절과 동일하다.
 * - session:token:{sha256(accessToken)}      → SessionData
 * - session:index:{memberId}:{deviceId}      → 현재 유효한 accessToken의 해시
 * - session:refresh:{sha256(refreshToken)}   → "memberId|deviceId" (1회용)
 *
 * refresh 시 역인덱스로 이전 해시를 찾아 즉시 삭제하고 새 해시로 교체하는 과정은
 * "읽고 → 비교/삭제 → 쓰기"가 여러 Redis 호출로 쪼개지면 그 사이에 동시 요청이
 * 끼어들 수 있으므로, equity-system-lab의 SafeRedisLock과 같은 이유로 Lua
 * 스크립트로 원자화한다([NEW-DESIGN] — facts/design 모두 "Lua로 원자화해야 한다"는
 * 세부까지는 명시하지 않았지만, 동시 refresh 요청 간 경쟁 상태를 막으려면 필요하다).
 */
public class ImprovedSessionManager implements SessionManager {

    private static final String TOKEN_PREFIX = "session:token:";
    private static final String INDEX_PREFIX = "session:index:";
    private static final String REFRESH_PREFIX = "session:refresh:";

    /**
     * KEYS[1] = 역인덱스 키, ARGV[1] = 새 해시, ARGV[2] = 세션 값, ARGV[3] = TTL(초).
     * 역인덱스에 남아있던 이전 해시가 있으면 그 세션을 먼저 지우고, 새 세션과
     * 역인덱스를 같은 원자적 실행 안에서 갱신한다.
     */
    private static final DefaultRedisScript<Long> ROTATE_SESSION = new DefaultRedisScript<>(
            "local oldHash = redis.call('GET', KEYS[1]) "
                    + "if oldHash then redis.call('DEL', 'session:token:' .. oldHash) end "
                    + "redis.call('SET', 'session:token:' .. ARGV[1], ARGV[2], 'EX', ARGV[3]) "
                    + "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3]) "
                    + "return 1",
            Long.class);

    private final StringRedisTemplate redis;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public ImprovedSessionManager(StringRedisTemplate redis, Duration accessTtl, Duration refreshTtl) {
        this.redis = redis;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    @Override
    public TokenPair issue(long memberId, String deviceId) {
        String accessToken = "at-" + UUID.randomUUID();
        String refreshToken = "rt-" + UUID.randomUUID();
        String accessHash = sha256(accessToken);

        redis.opsForValue().set(TOKEN_PREFIX + accessHash, new SessionData(memberId, deviceId).serialize(), accessTtl);
        redis.opsForValue().set(indexKey(memberId, deviceId), accessHash, accessTtl);
        storeRefreshRecord(refreshToken, memberId, deviceId);

        return new TokenPair(accessToken, refreshToken);
    }

    @Override
    public Optional<SessionData> lookup(String accessToken) {
        String raw = redis.opsForValue().get(TOKEN_PREFIX + sha256(accessToken));
        return raw == null ? Optional.empty() : Optional.of(SessionData.deserialize(raw));
    }

    /**
     * 이전 access token은 이 호출이 끝나는 순간부터 즉시 조회 실패한다(exp와 무관) —
     * facts 3번 섹션이 지적한 "TTL(수동적 만료)과 revoke(능동적 무효화)는 다른 문제"
     * 라는 구분을 해결하는 지점.
     */
    @Override
    public TokenPair refresh(String refreshToken, String deviceId) {
        String refreshHash = sha256(refreshToken);
        String raw = redis.opsForValue().get(REFRESH_PREFIX + refreshHash);
        if (raw == null) {
            throw new InvalidTokenException("refresh token not found or already used: " + refreshToken);
        }
        String[] parts = raw.split("\\|", 2);
        long memberId = Long.parseLong(parts[0]);
        String storedDeviceId = parts[1];
        if (!storedDeviceId.equals(deviceId)) {
            throw new InvalidTokenException("device mismatch for refresh token");
        }

        // 1회용 — 재사용(같은 refresh token으로 두 번 refresh)을 막는다.
        redis.delete(REFRESH_PREFIX + refreshHash);

        String newAccessToken = "at-" + UUID.randomUUID();
        String newRefreshToken = "rt-" + UUID.randomUUID();
        String newAccessHash = sha256(newAccessToken);

        redis.execute(ROTATE_SESSION,
                Collections.singletonList(indexKey(memberId, deviceId)),
                newAccessHash,
                new SessionData(memberId, deviceId).serialize(),
                String.valueOf(accessTtl.toSeconds()));

        storeRefreshRecord(newRefreshToken, memberId, deviceId);
        return new TokenPair(newAccessToken, newRefreshToken);
    }

    @Override
    public void revoke(String accessToken) {
        redis.delete(TOKEN_PREFIX + sha256(accessToken));
    }

    private void storeRefreshRecord(String refreshToken, long memberId, String deviceId) {
        redis.opsForValue().set(REFRESH_PREFIX + sha256(refreshToken), memberId + "|" + deviceId, refreshTtl);
    }

    private static String indexKey(long memberId, String deviceId) {
        return INDEX_PREFIX + memberId + ":" + deviceId;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
