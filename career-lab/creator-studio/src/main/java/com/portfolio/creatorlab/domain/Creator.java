package com.portfolio.creatorlab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [FACT 기반 재현] facts/projects/creator-studio.md 4번 섹션의 장애("A Creator가
 * b Producer로 재매핑되며 콘텐츠가 사라진 것처럼 보인 문제")를 재발시키지 않도록,
 * design/creator-studio.md 3.1절의 신규 결정 — "member 1명당 Creator는 최초 가입 시
 * 정확히 1개까지만 매핑된다" — 을 DB 유니크 제약(uq_creator_member)으로 강제한다.
 *
 * producerId를 바꾸는 유일한 경로는 {@link com.portfolio.creatorlab.onboarding
 * .ProducerMappingAdminService}를 통한 관리자 액션뿐이다 — 가입/재가입 흐름에서는
 * 이 필드가 절대 직접 덮어써지지 않는다(4번 장애 가설 ②의 재발 방지).
 */
@Entity
@Table(name = "creator", uniqueConstraints = @UniqueConstraint(name = "uq_creator_member", columnNames = "member_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Creator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "producer_id", nullable = false)
    private Long producerId;

    @Column(name = "display_name", nullable = false, length = 60)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CreatorStatus status = CreatorStatus.ACTIVE;

    public Creator(Long memberId, Long producerId, String displayName) {
        this.memberId = memberId;
        this.producerId = producerId;
        this.displayName = displayName;
        this.status = CreatorStatus.ACTIVE;
    }

    /**
     * [FACT 기반 재현] design 5.5절 {@code ProducerMappingAdminService.changeProducer()}
     * 에서만 호출되어야 하는 메서드 — 일반 가입 흐름({@code CreatorOnboardingService})은
     * 절대 이 메서드를 호출하지 않는다. 이 분리 자체가 4번 장애 가설 ②
     * ("Producer 생성이 기존 Creator의 producer_id를 덮어썼을 가능성")의 재발 방지 장치다.
     */
    public void reassignProducer(Long newProducerId) {
        this.producerId = newProducerId;
    }

    public enum CreatorStatus {
        ACTIVE, WITHDRAWN
    }
}
