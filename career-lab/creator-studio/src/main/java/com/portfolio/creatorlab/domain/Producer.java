package com.portfolio.creatorlab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [FACT 기반 재현] facts/projects/creator-studio.md 4번 섹션 — "모든 Creator는
 * Producer 하위에 속하며, 직접 가입 시 Producer를 먼저 생성"한다는 확정된 가입 도메인
 * 구조를 그대로 반영한다. representativeCreatorId 필드는 design 3.2절 ERD의
 * "대표 Creator(representative)" 관계를 재현한 것 — facts 4번 섹션에서 "B를 대표로
 * 설정했을 가능성이 있으나 정확히는 불확실"이라고 남긴 그 필드다.
 */
@Entity
@Table(name = "producer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Producer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "representative_creator_id")
    private Long representativeCreatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProducerStatus status = ProducerStatus.ACTIVE;

    public Producer(String name) {
        this.name = name;
        this.status = ProducerStatus.ACTIVE;
    }

    public void assignRepresentative(Long creatorId) {
        this.representativeCreatorId = creatorId;
    }

    public enum ProducerStatus {
        ACTIVE, SUSPENDED
    }
}
