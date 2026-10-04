package com.portfolio.creatorlab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [FACT 기반 재현] design/creator-studio.md 7.3절 — 원본 4번 장애 당시 DML 직접
 * 수정 대신 애플리케이션 정상 흐름으로 복구했던 판단을, 이번에는 처음부터 이력
 * 기록을 남기는 정식 관리자 기능으로 설계한 것. "매핑 변경 이력을 남긴다"는
 * design 3.2절 ERD의 확정 구조를 그대로 반영한다.
 */
@Entity
@Table(name = "producer_mapping_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProducerMappingHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(name = "from_producer_id")
    private Long fromProducerId;

    @Column(name = "to_producer_id", nullable = false)
    private Long toProducerId;

    @Column(name = "changed_by_member_id", nullable = false)
    private Long changedByMemberId;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    public ProducerMappingHistory(Long creatorId, Long fromProducerId, Long toProducerId,
                                   Long changedByMemberId, String reason) {
        this.creatorId = creatorId;
        this.fromProducerId = fromProducerId;
        this.toProducerId = toProducerId;
        this.changedByMemberId = changedByMemberId;
        this.reason = reason;
    }
}
