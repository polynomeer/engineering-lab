package com.portfolio.batchlab.batch.chunk;

import com.portfolio.batchlab.batch.BatchProperties;
import com.portfolio.batchlab.domain.SettlementRecord;
import com.portfolio.batchlab.repository.SettlementRecordRepository;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * [NEW-DESIGN] design/batch-excel-optimization.md 5.2절 개념 스케치를 그대로 구현한
 * keyset(커서) 기반 ItemReader다. offset 기반 JpaPagingItemReader 대신 이 방식을
 * 채택한 이유는 design 5.2절 그대로: 대량 레코드에서 OFFSET이 커질수록 스캔 비용이
 * 늘어나는 문제를 피하기 위해서다 — 실제 FLO가 이렇게 구현했다는 근거는 없다.
 *
 * ItemStreamReader로 구현해 lastSeenId를 ExecutionContext에 체크포인트로 남긴다 —
 * facts 16행(2026-09-09 확인)의 "Spring Batch가 기본 제공하는 JobRepository/Step
 * 실행 상태 추적을 그대로 활용"을 재현하는 부분이다. 다만 이 랩은 재실행/재시도
 * 시나리오 자체를 벤치마크 대상으로 삼지 않아(README의 스코프 참고) 체크포인트가
 * 실제로 쓰이는 통합 테스트는 별도로 두지 않았다.
 */
@Component
@StepScope
@RequiredArgsConstructor
public class SettlementRecordKeysetReader implements ItemStreamReader<SettlementRecord> {

    private static final String LAST_SEEN_ID_KEY = "settlement.reader.lastSeenId";

    private final SettlementRecordRepository repository;

    @Value("#{jobParameters['targetMonth']}")
    private String targetMonth;

    private long lastSeenId = 0L;
    private Iterator<SettlementRecord> currentPage = Collections.emptyIterator();

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        if (executionContext.containsKey(LAST_SEEN_ID_KEY)) {
            lastSeenId = executionContext.getLong(LAST_SEEN_ID_KEY);
        }
    }

    @Override
    public SettlementRecord read() {
        if (!currentPage.hasNext()) {
            List<SettlementRecord> page = repository.findByTargetMonthAndSettlementStatusAndIdGreaterThanOrderByIdAsc(
                    targetMonth,
                    SettlementRecord.Status.PENDING,
                    lastSeenId,
                    PageRequest.of(0, BatchProperties.CHUNK_SIZE));
            if (page.isEmpty()) {
                return null; // 종료 신호
            }
            currentPage = page.iterator();
        }
        if (!currentPage.hasNext()) {
            return null;
        }
        SettlementRecord next = currentPage.next();
        lastSeenId = next.getId();
        return next;
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putLong(LAST_SEEN_ID_KEY, lastSeenId);
    }

    @Override
    public void close() throws ItemStreamException {
        // no-op — 커넥션/리소스는 Repository(EntityManager)가 관리한다.
    }
}
