package com.portfolio.equitylab.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.redis.testcontainers.RedisContainer;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * facts/projects/equity-system.md 2번 항목("분산락 재설계와 상태 전이 기반
 * 정합성 확보")을 최소 단위로 재현한다. Spring 전체 컨텍스트가 필요 없어
 * MySQL 없이 Redis 컨테이너만 직접 띄운다.
 *
 * 시나리오는 실제로 있었던 정확한 장애 시퀀스가 아니라(그 정도로 상세한
 * 기록은 facts에 없음), "TTL 없음 + 무조건 DEL"이라는 확정된 버그
 * 메커니즘이 어떻게 두 프로세스의 동시 소유(race condition)로 이어지는지를
 * 결정론적으로(타이밍에 의존하지 않고) 재현한 것이다 — [NEW-DESIGN].
 */
@Testcontainers
@DisplayName("Redis 분산락 — TTL 없음+무조건 DEL(legacy) vs SETNX+Token+TTL(safe)")
class RedisLockRaceConditionTest {

    @Container
    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7.4-alpine"));

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void setUpRedis() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void tearDownRedis() {
        connectionFactory.destroy();
    }

    @AfterEach
    void clearKeys() {
        redisTemplate.delete("equity-job:KOMCA:2026-01");
    }

    @Test
    @DisplayName("legacy: 남의 락을 무조건 DEL해버려서 두 프로세스가 동시에 락을 쥔 것처럼 보이는 상황이 재현된다")
    void legacyLockAllowsConcurrentOwnership() {
        String key = "equity-job:KOMCA:2026-01";
        LegacyRedisLock lock = new LegacyRedisLock(redisTemplate);

        // 1) 프로세스 A가 락을 잡는다.
        boolean acquiredByA = lock.tryAcquire(key, "token-A");
        assertThat(acquiredByA).isTrue();

        // 2) (시나리오) 어떤 이유로든 — 운영자의 수동 조치, 혹은 다른 만료 메커니즘 등 —
        //    A가 모르는 사이에 키가 비워지고, 프로세스 B가 새로 락을 잡는다.
        //    "TTL이 없다"는 것 자체는 이 재획득을 유발하지 않지만, 이 랩이 재현하려는
        //    핵심 버그는 "A가 뒤늦게 release할 때 B의 락을 지워버리는가"이므로,
        //    그 전제 상황(B가 이미 락을 쥔 상태)만 결정론적으로 만들어준다.
        redisTemplate.delete(key);
        boolean acquiredByB = lock.tryAcquire(key, "token-B");
        assertThat(acquiredByB).isTrue();

        // 3) 뒤늦게 깨어난 A가 자신의 작업을 마치고 finally에서 무조건 DEL을 호출한다.
        lock.release(key, "token-A");

        // 4) BUG: B의 락이 A에 의해 지워져 버려서, 이제 아무도 안 쓰는데 키가 없다 —
        //    프로세스 C가 즉시 락을 잡을 수 있게 된다. B는 여전히 자기가 락을
        //    쥐고 있다고 믿고 작업 중인데(코드상 B의 참조는 살아있음), C도 동시에
        //    락을 획득한 것 — 두 프로세스가 동시에 "내가 락을 쥐고 있다"고 믿는
        //    상태(race condition)가 재현된다.
        boolean acquiredByC = lock.tryAcquire(key, "token-C");
        assertThat(acquiredByC)
                .as("legacy 구현의 버그: A의 무조건 DEL이 B의 락을 지워버려 C가 즉시 락을 가로챌 수 있다")
                .isTrue();
    }

    @Test
    @DisplayName("safe: 본인 토큰이 아니면 release가 무시되어 남의 락을 지우지 못한다")
    void safeLockPreventsCrossOwnerDeletion() {
        String key = "equity-job:KOMCA:2026-01";
        SafeRedisLock lock = new SafeRedisLock(redisTemplate);

        boolean acquiredByA = lock.tryAcquire(key, "token-A", Duration.ofSeconds(60));
        assertThat(acquiredByA).isTrue();

        // 동일한 전제: 어떤 이유로든 B가 이미 락을 쥔 상태를 만든다.
        redisTemplate.delete(key);
        boolean acquiredByB = lock.tryAcquire(key, "token-B", Duration.ofSeconds(60));
        assertThat(acquiredByB).isTrue();

        // A가 뒤늦게 release를 호출하지만, 토큰이 일치하지 않아 아무 일도 일어나지 않는다.
        boolean releasedByA = lock.release(key, "token-A");
        assertThat(releasedByA)
                .as("safe 구현: 토큰이 다르면 release는 아무것도 지우지 않고 false를 반환한다")
                .isFalse();

        // B의 락은 그대로 살아있다 — C는 락을 가로챌 수 없다.
        boolean acquiredByC = lock.tryAcquire(key, "token-C", Duration.ofSeconds(60));
        assertThat(acquiredByC)
                .as("safe 구현: B의 락이 보호되어 C는 락을 획득할 수 없다")
                .isFalse();

        // B가 정상적으로 자신의 토큰으로 release하면 그제서야 풀린다.
        boolean releasedByB = lock.release(key, "token-B");
        assertThat(releasedByB).isTrue();
        assertThat(lock.tryAcquire(key, "token-C", Duration.ofSeconds(60))).isTrue();
    }

    @Test
    @DisplayName("legacy: TTL이 없어 프로세스가 죽으면(release를 못 부르면) 락이 영원히 풀리지 않는다")
    void legacyLockNeverExpiresWithoutRelease() {
        String key = "equity-job:KOMCA:2026-01";
        LegacyRedisLock legacyLock = new LegacyRedisLock(redisTemplate);
        assertThat(legacyLock.tryAcquire(key, "token-A")).isTrue();

        // 3초를 기다려도 release를 호출하지 않으면 여전히 잠겨 있다 (TTL 없음의 liveness 버그).
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> assertThat(redisTemplate.hasKey(key)).isTrue());
    }

    @Test
    @DisplayName("safe: TTL이 지나면 release 없이도 자동으로 풀려 다른 프로세스가 획득할 수 있다")
    void safeLockExpiresAutomaticallyViaTtl() {
        String key = "equity-job:KOMCA:2026-01";
        SafeRedisLock safeLock = new SafeRedisLock(redisTemplate);
        assertThat(safeLock.tryAcquire(key, "token-A", Duration.ofSeconds(2))).isTrue();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(safeLock.tryAcquire(key, "token-B", Duration.ofSeconds(60)))
                        .as("TTL 만료 후에는 release 없이도 다른 프로세스가 락을 획득할 수 있어야 한다")
                        .isTrue());
    }
}
