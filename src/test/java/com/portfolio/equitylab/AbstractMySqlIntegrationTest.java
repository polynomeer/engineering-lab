package com.portfolio.equitylab;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.MySQLContainer;

/**
 * [NEW-DESIGN] 모든 통합 테스트가 공유하는 MySQL Testcontainers 베이스.
 * 컨테이너 자체는 static으로 한 번만 띄워 테스트 클래스 간 재사용한다(속도).
 *
 * 반면 Spring ApplicationContext(그리고 그 안의 HikariCP 커넥션 풀)는
 * {@code @DirtiesContext}로 테스트 클래스마다 새로 만들도록 강제한다 —
 * 여러 통합 테스트 클래스를 연달아 돌려보니, 같은 컨테이너를 가리키는데도
 * Spring이 컨텍스트를 캐시/재사용하는 과정에서 이전 컨텍스트의 HikariCP
 * 풀이 남기고 간 커넥션이 유효하지 않은 상태로 다음 클래스에 넘어가
 * "Failed to obtain JDBC Connection"이 재현됐다(2026-09-28, 이 랩에서 실제
 * 관찰). 컨테이너 재시작 비용(수 초) 없이 컨텍스트만 새로 만들면 이 문제가
 * 사라진다 — README에도 남겨둔다.
 */
@Testcontainers
@SpringBootTest(classes = EquityLabApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractMySqlIntegrationTest {

    // [NEW-DESIGN] innodb_buffer_pool_size를 일부러 작게 제한한다(기본값은 수백MB~).
    // 그래야 60만 건 규모에서도 "인덱스 없는 전체 스캔"이 실제로 디스크 I/O를
    // 유발해, facts에서 말하는 "광범위한 스캔·대량 I/O" 병목이 재현된다 —
    // 버퍼풀이 크면 이 정도 데이터는 메모리에 다 올라가 인덱스 유무와
    // 무관하게 둘 다 빨라져 버린다.
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.39")
            .withDatabaseName("equity_lab")
            .withUsername("equity_lab")
            .withPassword("equity_lab")
            .withCommand("--innodb-buffer-pool-size=24M", "--innodb-buffer-pool-instances=1")
            .withUrlParam("rewriteBatchedStatements", "true")
            .withUrlParam("useServerPrepStmts", "false");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
