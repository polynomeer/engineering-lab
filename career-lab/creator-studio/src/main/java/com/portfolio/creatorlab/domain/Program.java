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
 * [NEW-DESIGN] design/creator-studio.md 3.3절 DDL을 이 랩의 범위(콘텐츠 존재
 * 여부만 확인하면 되는 시나리오 2, Post-Commit 이벤트 발행 대상인 시나리오 3)에
 * 맞춰 최소 컬럼만 가져온 것 — Episode/Clip 계층은 이 랩에서 다루지 않는다.
 */
@Entity
@Table(name = "program")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Program {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "producer_id", nullable = false)
    private Long producerId;

    @Column(name = "created_by_creator_id", nullable = false)
    private Long createdByCreatorId;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProgramStatus status = ProgramStatus.DRAFT;

    public Program(Long producerId, Long createdByCreatorId, String title) {
        this.producerId = producerId;
        this.createdByCreatorId = createdByCreatorId;
        this.title = title;
        this.status = ProgramStatus.DRAFT;
    }

    public enum ProgramStatus {
        DRAFT, PUBLISHED, ARCHIVED
    }
}
