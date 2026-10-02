package com.portfolio.batchlab.batch.legacy;

import com.portfolio.batchlab.batch.BatchProperties;
import com.portfolio.batchlab.domain.SettlementRecord;
import com.portfolio.batchlab.repository.SettlementRecordRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * [FACT 기반 재현] facts/projects/batch-excel-optimization.md 15행의 Problem을
 * 그대로 재현하는 "before" 구현이다 — Tasklet 기반 **단일 트랜잭션** 구조.
 *
 * 이 Tasklet의 execute()는 Step 하나짜리 트랜잭션 안에서 통째로 실행된다
 * (아래 LegacyTaskletAggregationJobConfig 참고). 페이지 단위로 SELECT는 하지만
 * (DB round-trip 자체를 줄이기 위한 최적화일 뿐 메모리 문제의 핵심은 아니다),
 * entityManager.flush()/clear()를 한 번도 호출하지 않기 때문에 조회된 모든
 * SettlementRecord 엔티티가 트랜잭션이 끝날 때까지 영속성 컨텍스트에 계속
 * 관리 상태로 남는다 — 이것이 facts가 말하는 "대량 엔티티가 영속성 컨텍스트에
 * 계속 누적, 트랜잭션 종료 시점까지 메모리 미해제"다.
 *
 * id > lastSeenId 커서로 다음 페이지를 가져오는 것은 순전히 "이미 처리한 레코드를
 * 다시 읽지 않기 위한" 정확성 장치다 — 이 페이지네이션 자체가 flush/clear를
 * 대신하지는 않는다(그래서 offset이 아니라 keyset을 써도 문제는 재현된다).
 */
@Component
@StepScope
@RequiredArgsConstructor
public class LegacyAggregationTasklet implements Tasklet {

    private final SettlementRecordRepository repository;

    @Value("#{jobParameters['targetMonth']}")
    private String targetMonth;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        long lastSeenId = 0L;
        long processed = 0L;
        List<SettlementRecord> page;

        do {
            page = repository.findByTargetMonthAndSettlementStatusAndIdGreaterThanOrderByIdAsc(
                    targetMonth,
                    SettlementRecord.Status.PENDING,
                    lastSeenId,
                    PageRequest.of(0, BatchProperties.LEGACY_FETCH_PAGE_SIZE));

            for (SettlementRecord record : page) {
                record.markAggregated(); // 변경 감지(dirty checking)만 — flush는 트랜잭션 종료 시점에야 일어남
                lastSeenId = record.getId();
                processed++;
            }
            // 의도적으로 entityManager.flush()/clear()를 호출하지 않는다 — 이것이 재현하려는 Problem이다.
        } while (!page.isEmpty());

        contribution.incrementWriteCount(processed);
        return RepeatStatus.FINISHED;
    }
}
