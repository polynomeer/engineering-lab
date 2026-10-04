package com.portfolio.mdslab.repository;

import com.portfolio.mdslab.domain.ProcessedMessage;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * [NEW-DESIGN] {@link #tryRetryFailed}는 facts/design 어디에도 없던 부분이다 —
 * "메시지 ID 유니크 제약"만으로 멱등성을 구현하면 실패한 메시지가 같은 ID로
 * 재전달돼도 영원히 스킵되어 버리는 문제를 랩을 만들며 직접 발견했다(README 참고).
 * FAILED 상태일 때만 재시도를 허용하는 조건부 UPDATE로 이를 보완한다.
 *
 * <p>모든 {@code @Modifying} 메서드에 {@code @Transactional}을 직접 붙였다 —
 * SettlementLockRepository와 동일한 이유(README/그쪽 클래스 주석 참고):
 * 인터페이스에 직접 선언한 커스텀 쿼리 메서드는 SimpleJpaRepository의 기본
 * 트랜잭션을 물려받지 않는다.
 */
public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, String> {

    @Modifying
    @Transactional
    @Query("""
        UPDATE ProcessedMessage p
           SET p.status = com.portfolio.mdslab.domain.MessageStatus.PROCESSING,
               p.attemptCount = p.attemptCount + 1,
               p.processedAt = :now
         WHERE p.messageId = :messageId
           AND p.status = com.portfolio.mdslab.domain.MessageStatus.FAILED
        """)
    int tryRetryFailed(@Param("messageId") String messageId, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("""
        UPDATE ProcessedMessage p SET p.status = com.portfolio.mdslab.domain.MessageStatus.DONE
         WHERE p.messageId = :messageId
        """)
    int markDone(@Param("messageId") String messageId);

    @Modifying
    @Transactional
    @Query("""
        UPDATE ProcessedMessage p SET p.status = com.portfolio.mdslab.domain.MessageStatus.FAILED
         WHERE p.messageId = :messageId
        """)
    int markFailed(@Param("messageId") String messageId);
}
