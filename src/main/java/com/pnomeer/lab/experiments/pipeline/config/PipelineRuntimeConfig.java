package com.pnomeer.lab.experiments.pipeline.config;

import com.pnomeer.lab.experiments.pipeline.PipelineRunner;
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

    @org.springframework.context.annotation.DependsOn("experimentRunner")
    @Bean(name = "ingestTaskExecutor")
    public TaskExecutor ingestTaskExecutor(PipelineProperties properties, com.pnomeer.lab.storage.StorageProperties storage) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int inserterThreads = Math.max(1, properties.getThreads().getInserter());
        executor.setCorePoolSize(inserterThreads);
        executor.setMaxPoolSize(inserterThreads);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ingest-job-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(storage.shutdownTimeoutMs());
        executor.initialize();
        return executor;
    }

    @org.springframework.context.annotation.DependsOn("experimentRunner")
    @Bean(name = "experimentTaskExecutor")
    public TaskExecutor experimentTaskExecutor(com.pnomeer.lab.storage.StorageProperties storage) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("experiment-job-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(storage.shutdownTimeoutMs());
        executor.initialize();
        return executor;
    }
}
