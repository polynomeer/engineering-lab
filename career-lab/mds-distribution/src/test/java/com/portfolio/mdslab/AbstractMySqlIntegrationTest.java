package com.portfolio.mdslab;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * [NEW-DESIGN] equity-system-lab의 AbstractMySqlIntegrationTest와 동일한 패턴.
 *
 * - rewriteBatchedStatements/useServerPrepStmts는 이 랩에서는 대량 배치 삽입을
 *   하지 않아 성능상 필수는 아니지만, MySQL Testcontainers 사용 시 기본값으로
 *   깔아두는 것이 이후 확장(대량 시드 데이터 등)에 안전하다고 판단해 유지했다.
 * - 여러 @SpringBootTest 테스트 클래스가 같은 static 컨테이너를 공유할 때
 *   "Failed to obtain JDBC Connection"이 재현되는 문제를, equity-system-lab에서
 *   배운 대로 {@code @DirtiesContext(classMode = AFTER_CLASS)}로 방지한다 —
 *   컨테이너는 재사용하되 ApplicationContext(및 HikariCP 풀)는 클래스마다 새로
 *   만든다.
 */
@Testcontainers
@SpringBootTest(classes = MdsLabApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractMySqlIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.39")
            .withDatabaseName("mds_lab")
            .withUsername("mds_lab")
            .withPassword("mds_lab")
            .withUrlParam("rewriteBatchedStatements", "true")
            .withUrlParam("useServerPrepStmts", "false");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
