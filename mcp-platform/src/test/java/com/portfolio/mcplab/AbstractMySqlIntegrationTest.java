package com.portfolio.mcplab;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * [NEW-DESIGN] 모든 MySQL 통합 테스트가 공유하는 Testcontainers 베이스.
 * equity-system-lab(`/Users/hammac/Documents/equity-system-lab`)에서 이미 겪은 함정을
 * 그대로 반영한다 — 여러 {@code @SpringBootTest} 클래스가 static 컨테이너를 공유할 때
 * Spring ApplicationContext 캐싱이 꼬여 "Failed to obtain JDBC Connection"이 재현된
 * 적이 있어, {@code @DirtiesContext(classMode = AFTER_CLASS)}로 테스트 클래스마다
 * 컨텍스트(그리고 그 안의 HikariCP 풀)를 새로 만들도록 강제한다.
 *
 * <p>JDBC URL에 {@code rewriteBatchedStatements=true}, {@code useServerPrepStmts=false}를
 * 넣는 것도 같은 경험에서 나온 조치다 — 이 랩의 배치 크기는 크지 않지만 관례적으로
 * 동일하게 맞춘다.</p>
 */
@Testcontainers
@SpringBootTest(classes = McpLabApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractMySqlIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.39")
            .withDatabaseName("mcp_lab")
            .withUsername("mcp_lab")
            .withPassword("mcp_lab")
            .withUrlParam("rewriteBatchedStatements", "true")
            .withUrlParam("useServerPrepStmts", "false");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
