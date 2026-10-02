package com.portfolio.equitylab.lock;

import java.time.Duration;
import java.util.Collections;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * [FACT 기반 재현] "After" — facts/projects/equity-system.md 2번 항목의
 * Decision(2026-09-27 확정)을 그대로 재현한다: "SETNX + Lock Token 기반...
 * TTL을 적용...DEL 하기 전에 저장해둔 토큰과 현재 락의 토큰이 일치하는지
 * 검증한 뒤에만 삭제".
 *
 * GET-비교-DEL은 애플리케이션에서 두 번의 Redis 호출로 하면 그 사이에
 * 다시 race가 생길 수 있으므로, Lua 스크립트로 원자화한다
 * (facts/projects/equity-system.md의 Lua 예시와 동일한 표준 패턴).
 */
public class SafeRedisLock {

    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) "
                    + "else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public SafeRedisLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryAcquire(String key, String token, Duration ttl) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    /** 본인 토큰일 때만 원자적으로 삭제한다. 남의 락이면 아무 일도 하지 않는다. */
    public boolean release(String key, String token) {
        Long result = redisTemplate.execute(COMPARE_AND_DELETE, Collections.singletonList(key), token);
        return result != null && result == 1L;
    }
}
