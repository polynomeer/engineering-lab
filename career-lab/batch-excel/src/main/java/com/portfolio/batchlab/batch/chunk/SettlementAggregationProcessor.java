package com.portfolio.batchlab.batch.chunk;

import com.portfolio.batchlab.domain.SettlementRecord;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * [NEW-DESIGN] design/batch-excel-optimization.md 5.1절 아키텍처의
 * SettlementAggregationProcessor. 순수 도메인 계산(부수효과 없음)이라는
 * 설계 원칙을 그대로 따른다 — DB 접근은 Reader/Writer가 담당한다.
 */
@Component
public class SettlementAggregationProcessor implements ItemProcessor<SettlementRecord, SettlementRecord> {

    @Override
    public SettlementRecord process(SettlementRecord item) {
        item.markAggregated();
        return item;
    }
}
