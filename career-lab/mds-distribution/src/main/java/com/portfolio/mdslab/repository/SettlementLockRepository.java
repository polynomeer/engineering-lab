package com.portfolio.mdslab.repository;

import com.portfolio.mdslab.domain.SettlementLock;
import com.portfolio.mdslab.domain.SettlementTargetType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * [사실 근거 원리 + NEW-DESIGN 구현] design/mds-global-distribution.md 5.2절과 동일한
 * 형태의 리포지토리. {@link #tryAcquire}가 이 랩의 핵심 — 락 획득을 "체크 후 갱신"
 * 두 단계가 아니라 단일 조건부 UPDATE로 원자화해 race window를 없앤다(design 7.1절).
 *
 * <p>{@code @Modifying} 커스텀 쿼리 메서드에는 반드시 {@code @Transactional}을
 * 직접 붙여야 한다 — Spring Data JPA가 {@code SimpleJpaRepository}에 걸어주는
 * 기본 트랜잭션은 상속된 CRUD/파생 쿼리 메서드에만 적용되고, 인터페이스에
 * 직접 선언한 {@code @Query} 메서드에는 적용되지 않는다. 이걸 빼먹으면
 * 호출하는 쪽에 활성 트랜잭션이 없을 때 {@code TransactionRequiredException}이
 * 난다 — 이 랩을 만들며 실제로 겪은 함정(README 참고).
 */
public interface SettlementLockRepository extends JpaRepository<SettlementLock, Long> {

    Optional<SettlementLock> findByTargetTypeAndTargetId(SettlementTargetType targetType, Long targetId);

    /**
     * UNLOCKED이거나 TTL이 지난 LOCKED 행만 대상으로 삼아 원자적으로 락을 건다.
     * 영향받은 row 수가 1이면 이 호출자가 락을 획득한 것이고, 0이면 다른 실행이
     * 이미 유효한 락을 보유 중이라는 뜻이다(호출자는 이를 예외가 아니라 "스킵"으로
     * 처리해야 한다 — design 5.2절).
     */
    @Modifying
    @Transactional
    @Query("""
        UPDATE SettlementLock l
           SET l.status = com.portfolio.mdslab.domain.LockStatus.LOCKED,
               l.lockedAt = :now,
               l.expiresAt = :expiresAt,
               l.lockedBy = :runId,
               l.version = l.version + 1
         WHERE l.targetType = :targetType AND l.targetId = :targetId
           AND (l.status = com.portfolio.mdslab.domain.LockStatus.UNLOCKED OR l.expiresAt < :now)
        """)
    int tryAcquire(
            @Param("targetType") SettlementTargetType targetType,
            @Param("targetId") Long targetId,
            @Param("now") LocalDateTime now,
            @Param("expiresAt") LocalDateTime expiresAt,
            @Param("runId") String runId);

    /** 본인(runId)이 쥔 락만 해제한다 — 다른 실행이 TTL 만료 후 재획득한 락을 실수로 풀지 않기 위함. */
    @Modifying
    @Transactional
    @Query("""
        UPDATE SettlementLock l
           SET l.status = com.portfolio.mdslab.domain.LockStatus.UNLOCKED,
               l.lockedAt = null,
               l.expiresAt = null,
               l.lockedBy = null
         WHERE l.targetType = :targetType AND l.targetId = :targetId AND l.lockedBy = :runId
        """)
    int release(
            @Param("targetType") SettlementTargetType targetType,
            @Param("targetId") Long targetId,
            @Param("runId") String runId);
}
