package com.portfolio.mdslab.settlement;

import com.portfolio.mdslab.domain.SettlementTargetType;
import java.time.LocalDateTime;

/** [사실 근거] 정산 중 메타데이터 수정 요청을 사전 차단할 때 던지는 예외. */
public class SettlementInProgressException extends RuntimeException {

    private final SettlementTargetType targetType;
    private final Long targetId;
    private final LocalDateTime expiresAt;

    public SettlementInProgressException(SettlementTargetType targetType, Long targetId, LocalDateTime expiresAt) {
        super("정산 진행 중에는 메타데이터를 수정할 수 없습니다. targetType=%s, targetId=%d, expiresAt=%s"
                .formatted(targetType, targetId, expiresAt));
        this.targetType = targetType;
        this.targetId = targetId;
        this.expiresAt = expiresAt;
    }

    public SettlementTargetType getTargetType() {
        return targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
