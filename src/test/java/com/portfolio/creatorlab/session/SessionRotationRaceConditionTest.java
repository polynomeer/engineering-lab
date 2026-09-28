package com.portfolio.creatorlab.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redis.testcontainers.RedisContainer;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * facts/projects/creator-studio.md 3번 섹션("인증·세션 구조와 refresh 이후 old
 * token 통과 문제")을 결정론적으로 재현한다. equity-system-lab의
 * RedisLockRaceConditionTest와 같은 이유로 Spring 컨텍스트 없이 Redis
 * Testcontainers만 직접 띄운다 — 세션 조회 성패만 확인하면 되므로 전체 애플리케이션
 * 컨텍스트가 필요 없다.
 */
@Testcontainers
@DisplayName("세션 토큰 로테이션 — 원문 key(legacy) vs 해시 key+역인덱스(improved)")
class SessionRotationRaceConditionTest {

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

    @BeforeEach
    void clearKeys() {
        var keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("[Before, FACT 기반 재현] refresh 후에도 옛 access token으로 세션 조회가 계속 통과된다")
    void legacySessionManagerStillAcceptsOldAccessTokenAfterRefresh() {
        LegacySessionManager legacy = new LegacySessionManager(redisTemplate, Duration.ofMinutes(30), Duration.ofDays(14));

        TokenPair original = legacy.issue(1001L, "device-A");
        assertThat(legacy.lookup(original.accessToken())).isPresent();

        // FE가 refresh해서 새 access token을 받는다.
        TokenPair rotated = legacy.refresh(original.refreshToken(), "device-A");

        // BUG: 새 토큰도 유효하지만, 옛 토큰도 exp가 남아있는 한 여전히 유효하다 —
        // Studio가 "새 token이 발급됐으니 이전 token은 폐기한다"는 능동적 무효화를
        // 전혀 하지 않기 때문이다(facts 3번 섹션).
        assertThat(legacy.lookup(rotated.accessToken()))
                .as("새 access token은 당연히 유효해야 한다")
                .isPresent();
        assertThat(legacy.lookup(original.accessToken()))
                .as("BUG: refresh로 새 token을 받은 뒤에도 옛 access token이 여전히 세션을 통과시킨다")
                .isPresent();
    }

    @Test
    @DisplayName("[After, FACT 기반 재현 — 미구현이었던 개선안을 실제로 구현] refresh 즉시 옛 access token은 거절된다")
    void improvedSessionManagerRejectsOldAccessTokenImmediatelyAfterRefresh() {
        ImprovedSessionManager improved =
                new ImprovedSessionManager(redisTemplate, Duration.ofMinutes(30), Duration.ofDays(14));

        TokenPair original = improved.issue(2002L, "device-B");
        assertThat(improved.lookup(original.accessToken())).isPresent();

        TokenPair rotated = improved.refresh(original.refreshToken(), "device-B");

        assertThat(improved.lookup(rotated.accessToken()))
                .as("새 access token은 유효해야 한다")
                .isPresent();
        assertThat(improved.lookup(original.accessToken()))
                .as("개선: exp가 남아있어도 refresh 시점부터 즉시 거절되어야 한다(revoke, TTL과는 별개)")
                .isEmpty();
    }

    @Test
    @DisplayName("[After] 역인덱스 덕분에 refresh 이후에도 sub+device당 세션은 정확히 하나만 남는다")
    void improvedSessionManagerKeepsExactlyOneSessionPerSubjectAndDevice() {
        ImprovedSessionManager improved =
                new ImprovedSessionManager(redisTemplate, Duration.ofMinutes(30), Duration.ofDays(14));

        TokenPair t1 = improved.issue(3003L, "device-C");
        TokenPair t2 = improved.refresh(t1.refreshToken(), "device-C");
        TokenPair t3 = improved.refresh(t2.refreshToken(), "device-C");

        assertThat(improved.lookup(t1.accessToken())).isEmpty();
        assertThat(improved.lookup(t2.accessToken())).isEmpty();
        assertThat(improved.lookup(t3.accessToken()))
                .as("가장 최근에 발급된 token만 유효해야 한다")
                .isPresent()
                .get()
                .satisfies(session -> {
                    assertThat(session.memberId()).isEqualTo(3003L);
                    assertThat(session.deviceId()).isEqualTo("device-C");
                });
    }

    @Test
    @DisplayName("[After] 한 번 사용된 refresh token은 재사용할 수 없다")
    void improvedSessionManagerRejectsReusedRefreshToken() {
        ImprovedSessionManager improved =
                new ImprovedSessionManager(redisTemplate, Duration.ofMinutes(30), Duration.ofDays(14));

        TokenPair original = improved.issue(4004L, "device-D");
        improved.refresh(original.refreshToken(), "device-D");

        assertThatThrownBy(() -> improved.refresh(original.refreshToken(), "device-D"))
                .isInstanceOf(InvalidTokenException.class);
    }
}
