package com.portfolio.batchlab;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * [NEW-DESIGN] 모든 통합 테스트가 공유하는 MySQL Testcontainers 베이스.
 * equity-system-lab의 AbstractMySqlIntegrationTest와 동일한 구성이다 — 그 랩에서
 * 실제로 겪은 두 가지 함정을 그대로 반영한다.
 *
 * 1) rewriteBatchedStatements=true / useServerPrepStmts=false가 없으면 대량 INSERT가
 *    개별 round-trip으로 나가 수십 분이 걸린다(지분율 랩에서 43분짜리 삽질 경험).
 * 2) 여러 @SpringBootTest 테스트 클래스가 같은 static 컨테이너를 공유할 때,
 *    Spring의 ApplicationContext 캐싱이 꼬여 이전 클래스가 쓰던 HikariCP 풀이
 *    "Failed to obtain JDBC Connection"을 내는 문제가 있었다 — 컨테이너는
 *    재시작하지 않고 컨텍스트만 새로 만들도록 @DirtiesContext(AFTER_CLASS)로
 *    해결한다.
 *
 * 이 랩은 (equity-system-lab과 달리) innodb_buffer_pool_size를 일부러 줄이지
 * 않는다 — 여기서 비교하는 것은 "인덱스 유무에 따른 DB I/O 병목"이 아니라
 * "애플리케이션 프로세스의 JVM 힙 사용량"이라 버퍼풀 크기가 측정 대상과
 * 무관하다.
 */
@Testcontainers
@SpringBootTest(classes = BatchLabApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractMySqlIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.39")
            .withDatabaseName("batch_lab")
            .withUsername("batch_lab")
            .withPassword("batch_lab")
            .withUrlParam("rewriteBatchedStatements", "true")
            .withUrlParam("useServerPrepStmts", "false");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
