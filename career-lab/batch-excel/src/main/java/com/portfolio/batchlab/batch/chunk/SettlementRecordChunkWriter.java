package com.portfolio.batchlab.batch.chunk;

import com.portfolio.batchlab.domain.SettlementRecord;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

/**
 * [FACT 기반 재현] facts 16행 Decision의 핵심 — "일정 건수마다 flush()/clear()
 * 수행해 영속성 컨텍스트 크기 제어". design 5.3절 그대로: chunk size와 flush/clear
 * 주기를 분리하지 않고 chunk-oriented step의 writer 종료 시점에 맞춰 일치시킨다.
 *
 * 이 랩에서 reader가 넘겨주는 SettlementRecord는 이미 같은 트랜잭션(chunk
 * 트랜잭션) 안에서 JpaRepository로 조회된 **관리 상태(managed) 엔티티**이고,
 * processor(SettlementAggregationProcessor)가 그 관리 엔티티의 필드를 바로
 * 변경했으므로 변경 감지(dirty checking)만으로 UPDATE 대상이 된다 — 별도로
 * persist()/merge()를 호출할 필요가 없다(design 5.3절 스케치는 "실제 코드
 * 아님"이라 명시된 개념 예시였고, 이 재구현에서는 필요 없는 호출로 확인했다).
 * flush()로 대기 중인 UPDATE를 DB에 반영한 뒤, clear()로 영속성 컨텍스트를
 * 비워 다음 청크가 시작될 때 메모리를 0에 가깝게 리셋한다.
 */
@Component
@RequiredArgsConstructor
public class SettlementRecordChunkWriter implements ItemWriter<SettlementRecord> {

    private final EntityManager entityManager;

    @Override
    public void write(Chunk<? extends SettlementRecord> chunk) {
        entityManager.flush();
        entityManager.clear(); // 영속성 컨텍스트 크기를 chunk 경계에서 매번 리셋 [FACT 원리 재현]
    }
}
