package com.portfolio.equitylab.lock;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * [FACT 기반 재현] "Before" — facts/projects/equity-system.md 2번 항목의
 * Problem(2026-09-27 확정)을 그대로 재현한다: "TTL 없이, finally 블록에서
 * 소유권 검증 없이 무조건 DEL을 호출하는 구조".
 *
 * 두 가지 버그가 있다.
 * 1. TTL이 없다 — 프로세스가 예외로 죽으면 락이 영원히 풀리지 않는다(liveness 버그).
 * 2. release()가 token을 확인하지 않고 무조건 DEL한다 — 어떤 이유로든 락 키가
 *    다른 프로세스 소유로 바뀐 뒤에 원래 프로세스가 뒤늦게 release를 호출하면,
 *    남의 락을 지워버린다(safety 버그, race condition의 직접 원인).
 */
public class LegacyRedisLock {

    private final StringRedisTemplate redisTemplate;

    public LegacyRedisLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** SETNX만 쓰고 TTL은 절대 걸지 않는다 — 버그를 있는 그대로 재현한다. */
    public boolean tryAcquire(String key, String token) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token);
        return Boolean.TRUE.equals(acquired);
    }

    /** BUG: token을 확인하지 않고 무조건 삭제한다. */
    public void release(String key, String token) {
        redisTemplate.delete(key);
    }
}
