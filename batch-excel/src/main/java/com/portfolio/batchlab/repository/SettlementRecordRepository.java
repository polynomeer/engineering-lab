package com.portfolio.batchlab.repository;

import com.portfolio.batchlab.domain.SettlementRecord;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * [NEW-DESIGN] design/batch-excel-optimization.md 5.2절의 keyset 페이지네이션을
 * 그대로 따른다 — offset 대신 "id > lastSeenId" 커서로 다음 페이지를 가져온다.
 * idx_settlement_month_status_id(target_month, settlement_status, id) 인덱스를
 * 그대로 사용하도록 조건 순서를 맞췄다. List를 반환해(Page가 아님) 매 페이지마다
 * 불필요한 COUNT 쿼리가 나가지 않도록 했다 — 종료 조건은 "빈 리스트가 올 때까지"다.
 */
public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, Long> {

    List<SettlementRecord> findByTargetMonthAndSettlementStatusAndIdGreaterThanOrderByIdAsc(
            String targetMonth,
            SettlementRecord.Status status,
            Long lastSeenId,
            Pageable pageable);

    long countByTargetMonthAndSettlementStatus(String targetMonth, SettlementRecord.Status status);
}
