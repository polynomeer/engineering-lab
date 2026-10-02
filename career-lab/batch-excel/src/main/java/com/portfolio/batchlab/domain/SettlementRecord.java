package com.portfolio.batchlab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [NEW-DESIGN] career-hub design/batch-excel-optimization.md 1.3절에서 정의한
 * 가상 도메인("구독 정산 데이터 대량 집계") 중 배치·Excel 두 시나리오가 실제로
 * 읽고 쓰는 대상 테이블. 이 엔티티 자체(필드 구성, 상태값)는 실제 FLO 시스템의
 * 사실이 아니라 이 재구현을 위해 새로 설계한 것이다.
 *
 * [FACT 기반 재현] 이 엔티티가 대량으로 영속성 컨텍스트에 쌓이는 것 자체가
 * facts/projects/batch-excel-optimization.md 15행의 Problem
 * ("Tasklet 기반 단일 트랜잭션 구조 → 대량 엔티티가 영속성 컨텍스트에 계속 누적")을
 * 재현하는 장치다.
 */
@Entity
@Table(name = "settlement_record")
@Getter
@NoArgsConstructor
public class SettlementRecord {

    public enum Status {
        PENDING,
        AGGREGATED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "target_month", nullable = false, length = 7)
    private String targetMonth;

    @Column(name = "gross_amount", nullable = false)
    private int grossAmount;

    @Column(name = "fee_amount", nullable = false)
    private int feeAmount;

    @Column(name = "net_amount", nullable = false)
    private int netAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", nullable = false, length = 20)
    private Status settlementStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public SettlementRecord(Long subscriptionId, String targetMonth, int grossAmount, int feeAmount) {
        this.subscriptionId = subscriptionId;
        this.targetMonth = targetMonth;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount;
        this.netAmount = grossAmount - feeAmount;
        this.settlementStatus = Status.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * [FACT 기반 재현] "집계" 자체의 계산 로직은 facts에 없으므로(도메인이 가상이므로)
     * 단순화했다 — 두 시나리오가 실제로 검증하려는 것은 집계 로직의 정교함이 아니라
     * "대량 엔티티를 어떻게 처리하느냐(메모리 관점)"이기 때문에, 상태 전이만으로
     * Problem을 재현하는 데는 충분하다고 판단했다. [NEW-DESIGN]
     */
    public void markAggregated() {
        this.settlementStatus = Status.AGGREGATED;
    }
}
