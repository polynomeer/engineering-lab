package com.portfolio.creatorlab.integration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * [FACT 기반 재현] facts/projects/creator-studio.md 1번 섹션의 확정된 구조 —
 * "도메인 로직은 이벤트 발행만 담당, 외부 호출은 커밋 이후 비동기 처리. 재시도는
 * 3회로 제한하고 최종 실패 시에는 별도 재처리 없이 알림(로그)만 남기는 정책."
 * Mixpanel과 완전히 독립적으로 동작한다(순서 보장 없음, 서로의 실패에 영향받지 않음
 * — 2026-09-09 사용자 확인).
 *
 * backoff 파라미터(500ms, x2)는 [NEW-DESIGN] — facts에는 "3회 재시도"라는 정책만 확인됐다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrazeEventListener {

    private final BrazeClient brazeClient;

    @Async("externalIntegrationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Retryable(retryFor = ExternalIntegrationException.class, maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2))
    public void onContentChanged(ContentChangedEvent event) {
        brazeClient.track(event);
    }

    @Recover
    public void recover(ExternalIntegrationException e, ContentChangedEvent event) {
        // 최종 실패 — 재처리하지 않고 로그만 남긴다(통계 성격상 미스카운트 허용, facts 1번 섹션).
        log.warn("[EXTERNAL_INTEGRATION_FAILED] target=braze contentType={} contentId={}",
                event.contentType(), event.contentId(), e);
    }
}
