package com.portfolio.mdslab.domain;

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
 * [NEW-DESIGN] design/mds-global-distribution.md 3.3절 설계를 그대로 옮긴 엔티티.
 * facts에는 "정산 대상 단위별 상태 플래그 + TTL"이라는 개념만 확인되어 있고
 * 정확한 컬럼 구성은 이 재구현을 위해 새로 정했다.
 *
 * 락 획득/해제는 절대 이 엔티티의 세터로 하지 않는다 — 반드시
 * {@link com.portfolio.mdslab.repository.SettlementLockRepository#tryAcquire}
 * 같은 원자적 조건부 UPDATE를 통해서만 상태를 바꾼다(레이스 윈도우 제거,
 * design 7.1절). 이 클래스는 "현재 상태를 읽어 활성 여부를 판단하는" 읽기
 * 전용 헬퍼({@link #isActiveConsidering})만 제공한다.
 */
@Entity
@Table(name = "settlement_lock")
@Getter
@NoArgsConstructor
public class SettlementLock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    private SettlementTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LockStatus status = LockStatus.UNLOCKED;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(nullable = false)
    private long version;

    public SettlementLock(SettlementTargetType targetType, Long targetId) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.status = LockStatus.UNLOCKED;
        this.version = 0L;
    }

    /**
     * [사실 근거 원리 + NEW-DESIGN 구현] TTL 지연 평가(lazy evaluation) 방식 자동 복구.
     * design 6.1절 노트: "release 호출 없이 TTL 경과 시, 다음 조회에서
     * isActiveConsidering()이 false를 반환해 사실상 UNLOCKED로 취급" — 별도의
     * 복구 배치를 두지 않고, 조회 시점마다 만료 여부를 판단하는 방식으로
     * "비정상 종료 시 락이 영구히 풀리지 않는" 문제를 해결한다(F-5).
     */
    public boolean isActiveConsidering(LocalDateTime now) {
        if (status != LockStatus.LOCKED) {
            return false;
        }
        return expiresAt == null || now.isBefore(expiresAt);
    }
}
