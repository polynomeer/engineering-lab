package com.portfolio.creatorlab;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * [NEW-DESIGN] equity-system-lab의 AbstractMySqlIntegrationTest와 동일한 패턴.
 * 컨테이너는 static으로 공유해 재시작 비용을 없애되, Spring ApplicationContext(및
 * 그 안의 HikariCP 풀)는 {@code @DirtiesContext(AFTER_CLASS)}로 테스트 클래스마다
 * 새로 만들어 "Failed to obtain JDBC Connection" 문제를 예방한다(equity-system-lab
 * README "실행하며 실제로 배운 것" 3번 참고 — 이 랩에서도 동일한 함정을 피하기 위해
 * 처음부터 적용).
 */
@Testcontainers
@SpringBootTest(classes = CreatorLabApplication.class, webEnvironment = WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractMySqlIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.39")
            .withDatabaseName("creator_studio_lab")
            .withUsername("creator_studio_lab")
            .withPassword("creator_studio_lab")
            .withUrlParam("rewriteBatchedStatements", "true")
            .withUrlParam("useServerPrepStmts", "false");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
