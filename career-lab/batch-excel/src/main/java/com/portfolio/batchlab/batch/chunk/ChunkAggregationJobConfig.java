package com.portfolio.batchlab.batch.chunk;

import com.portfolio.batchlab.batch.BatchProperties;
import com.portfolio.batchlab.domain.SettlementRecord;
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
 * [FACT 기반 재현] facts 16행 Decision의 "Chunk 기반 트랜잭션으로 전환" 그대로.
 * design/batch-excel-optimization.md 5.3절 Job/Step 정의를 따른다.
 *
 * faultTolerant() + skipLimit(0): design 5.3절과 동일 — 정산 데이터는 레코드
 * 단위 스킵을 허용하지 않는다(스킵하면 그 레코드만 조용히 누락된다). 실패하면
 * 청크(트랜잭션) 전체가 롤백되고, Job이 실패 상태로 끝난다 — 재실행은 동일
 * JobParameters로 사람이 다시 트리거해야 한다(facts 16행, 2026-09-09 확인 사실과
 * 동일한 재실행 모델). 이 랩은 재실행 자체를 별도 테스트로 검증하지는 않는다
 * (README 스코프 참고 — 이 랩의 벤치마크 대상은 메모리다).
 */
@Configuration
@RequiredArgsConstructor
public class ChunkAggregationJobConfig {

    public static final String JOB_NAME = "chunkAggregationJob";
    public static final String STEP_NAME = "aggregateSettlementStep";

    @Bean
    public Job chunkAggregationJob(JobRepository jobRepository, Step aggregateSettlementStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(aggregateSettlementStep)
                .build();
    }

    @Bean
    public Step aggregateSettlementStep(JobRepository jobRepository,
                                         PlatformTransactionManager transactionManager,
                                         SettlementRecordKeysetReader settlementRecordKeysetReader,
                                         SettlementAggregationProcessor settlementAggregationProcessor,
                                         SettlementRecordChunkWriter settlementRecordChunkWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<SettlementRecord, SettlementRecord>chunk(BatchProperties.CHUNK_SIZE, transactionManager)
                .reader(settlementRecordKeysetReader)
                .processor(settlementAggregationProcessor)
                .writer(settlementRecordChunkWriter)
                .faultTolerant()
                .skipLimit(0)
                .build();
    }
}
