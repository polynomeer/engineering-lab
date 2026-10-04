package com.portfolio.batchlab.batch.legacy;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * [FACT 기반 재현] "Tasklet 기반 단일 트랜잭션" Job 정의. facts 16행이 말하는
 * before 구조 그대로다 — Step이 하나의 Tasklet이고, 그 Tasklet 전체가 하나의
 * 트랜잭션으로 묶인다(StepBuilder#tasklet(..., transactionManager)의 기본 동작).
 */
@Configuration
@RequiredArgsConstructor
public class LegacyTaskletAggregationJobConfig {

    public static final String JOB_NAME = "legacyTaskletAggregationJob";
    public static final String STEP_NAME = "legacyTaskletStep";

    @Bean
    public Job legacyTaskletAggregationJob(JobRepository jobRepository, Step legacyTaskletStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(legacyTaskletStep)
                .build();
    }

    @Bean
    public Step legacyTaskletStep(JobRepository jobRepository,
                                   PlatformTransactionManager transactionManager,
                                   LegacyAggregationTasklet legacyAggregationTasklet) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(legacyAggregationTasklet, transactionManager)
                .build();
    }
}
