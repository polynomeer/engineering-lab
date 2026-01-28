package com.pnomeer.pipeline.config;

import com.pnomeer.pipeline.PipelineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class PipelineRuntimeConfig {

    @Bean
    public PipelineRunner pipelineRunner(
            PipelineProperties properties,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        return new PipelineRunner(properties, jdbcTemplate, transactionTemplate);
    }

    @Bean(name = "ingestTaskExecutor")
    public TaskExecutor ingestTaskExecutor(PipelineProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int inserterThreads = Math.max(1, properties.getThreads().getInserter());
        executor.setCorePoolSize(inserterThreads);
        executor.setMaxPoolSize(inserterThreads);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ingest-job-");
        executor.initialize();
        return executor;
    }
}
