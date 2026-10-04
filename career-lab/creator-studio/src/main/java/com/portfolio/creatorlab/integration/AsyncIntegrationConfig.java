package com.portfolio.creatorlab.integration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * [NEW-DESIGN] design/creator-studio.md 8절 — "외부 연동 스레드풀: core 4 / max 8"는
 * facts에 없는 값이라 이 랩을 위해 새로 정한 것.
 */
@Configuration
public class AsyncIntegrationConfig {

    @Bean(name = "externalIntegrationExecutor")
    public ThreadPoolTaskExecutor externalIntegrationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ext-integration-");
        executor.initialize();
        return executor;
    }
}
