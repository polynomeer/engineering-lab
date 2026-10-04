package com.portfolio.mdslab.settlement;

import com.portfolio.mdslab.domain.SettlementLock;
import com.portfolio.mdslab.domain.SettlementTargetType;
import com.portfolio.mdslab.repository.SettlementLockRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [FACT 기반 재현] 정산 배치 역할을 하는 최소 서비스. 실제 FLO의 정산 계산
 * 로직은 이 랩의 시연 범위 밖이라(퇴사 후 접근 불가) 재현하지 않고, "락을
 * 잡고 → (정산 계산, 생략) → 락을 푼다"는 락 생명주기만 재현한다(design
 * 9장 Phase 6와 동일한 스코프 축소).
 */
@Service
@RequiredArgsConstructor
public class SettlementBatchService {

    private final SettlementLockRepository lockRepository;

    /**
     * 대상 락 row가 없으면 먼저 UNLOCKED로 만들어둔 뒤(최초 1회) tryAcquire를
     * 시도한다. tryAcquire의 반환값(영향 row 수)이 1이면 이 호출자가 락을
     * 획득한 것이다.
     */
    @Transactional
    public boolean acquireLock(SettlementTargetType type, Long targetId, String runId, Duration ttl) {
        ensureLockRowExists(type, targetId);
        LocalDateTime now = LocalDateTime.now();
        int updated = lockRepository.tryAcquire(type, targetId, now, now.plus(ttl), runId);
        return updated == 1;
    }

    /** 본인이 쥔 락만 해제된다 — TTL 만료 후 다른 실행이 재획득한 락을 실수로 풀지 않는다. */
    @Transactional
    public boolean releaseLock(SettlementTargetType type, Long targetId, String runId) {
        return lockRepository.release(type, targetId, runId) == 1;
    }

    private void ensureLockRowExists(SettlementTargetType type, Long targetId) {
        if (lockRepository.findByTargetTypeAndTargetId(type, targetId).isEmpty()) {
            lockRepository.save(new SettlementLock(type, targetId));
        }
    }
}
