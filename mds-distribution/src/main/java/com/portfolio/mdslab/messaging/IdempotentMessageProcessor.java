package com.portfolio.mdslab.messaging;

import com.portfolio.mdslab.domain.ProcessedMessage;
import com.portfolio.mdslab.repository.ProcessedMessageRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [사실 근거 원리 + NEW-DESIGN 구현] "멱등 처리는 메시지 ID에 DB 유니크 제약을
 * 걸어 중복 처리를 막는 방식으로 구현" (facts 2번 Decision, 2026-09-09 확인).
 *
 * facts/design의 일러스트레이션 코드는 단순 INSERT 1회 시도였지만, 이 랩을
 * 만들며 그 방식의 한계를 직접 발견했다: 메시지가 처리에 "실패"한 뒤 같은
 * messageId로 재전달되면, INSERT는 항상 유니크 제약 위반이 나므로 영원히
 * 스킵되어 버린다 — 실패한 메시지가 재시도되지 못하는 것은 "멱등 처리"가
 * 아니라 "메시지 유실"이다. 그래서 두 단계로 나눴다({@link #tryInsertNew},
 * {@link #tryRetryFailed}): 신규 메시지는 INSERT로 잡고, 이미 존재하는데
 * 상태가 FAILED인 메시지만 조건부 UPDATE로 재시도를 허용한다. DONE/PROCESSING
 * 상태는 계속 스킵된다 — 이게 진짜 멱등성이다(README 참고).
 *
 * {@link #tryInsertNew}/{@link #tryRetryFailed}에는 일부러 {@code @Transactional}을
 * 붙이지 않았다 — 이 랩을 만들며 실제로 겪은 함정(README 참고): 같은 클래스
 * 안에서 {@code this.tryInsertNew(...)}처럼 자기 자신을 호출(self-invocation)하면
 * 스프링 프록시를 거치지 않아 {@code @Transactional}이 조용히 무시된다.
 * 대신 이 두 메서드는 각각 리포지토리(별도의 스프링 빈, 프록시 대상) 호출을
 * 정확히 한 번씩만 하므로, {@code JpaRepository}가 자기 자신의 CRUD/커스텀
 * 쿼리 메서드에 기본으로 걸어주는 트랜잭션 경계만으로 충분하다 — 굳이 이
 * 클래스에서 감쌀 필요가 없다. {@link #markDone}/{@link #markFailed}는 이
 * 클래스 바깥({@code AlbumRegistrationListener})에서 주입받은 빈을 통해
 * 호출되므로, 여기 붙인 {@code @Transactional}은 정상적으로 프록시를 통해
 * 적용된다.
 */
@Component
@RequiredArgsConstructor
public class IdempotentMessageProcessor {

    private final ProcessedMessageRepository repository;

    /** true면 이 호출자가 처리를 진행해야 한다(신규 메시지 또는 재시도 허용된 실패 메시지). */
    public boolean tryBegin(String messageId, String messageType) {
        if (tryInsertNew(messageId, messageType)) {
            return true;
        }
        return tryRetryFailed(messageId);
    }

    private boolean tryInsertNew(String messageId, String messageType) {
        try {
            repository.saveAndFlush(new ProcessedMessage(messageId, messageType));
            return true;
        } catch (DataAccessException e) {
            // 유니크 제약 위반(DataIntegrityViolationException)이 정상 케이스지만,
            // 동시에 같은 PK로 INSERT가 몰리면 MySQL이 잠금 대기/데드락 계열
            // 예외(CannotAcquireLockException 등)를 던지는 경우도 실제로
            // 관찰했다(README 참고) — 둘 다 "누군가 이미 처리 중/처리했다"는
            // 뜻이므로 DataAccessException 상위 타입으로 함께 묶어 스킵 처리한다.
            return false;
        }
    }

    private boolean tryRetryFailed(String messageId) {
        return repository.tryRetryFailed(messageId, LocalDateTime.now()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(String messageId) {
        repository.markDone(messageId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String messageId) {
        repository.markFailed(messageId);
    }
}
