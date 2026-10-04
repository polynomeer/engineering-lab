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

/** [FACT 기반 재현] BrazeEventListener와 완전히 독립적인 별도 리스너 — facts 1번 섹션 동일 근거. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MixpanelEventListener {

    private final MixpanelClient mixpanelClient;

    @Async("externalIntegrationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Retryable(retryFor = ExternalIntegrationException.class, maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2))
    public void onContentChanged(ContentChangedEvent event) {
        mixpanelClient.track(event);
    }

    @Recover
    public void recover(ExternalIntegrationException e, ContentChangedEvent event) {
        log.warn("[EXTERNAL_INTEGRATION_FAILED] target=mixpanel contentType={} contentId={}",
                event.contentType(), event.contentId(), e);
    }
}
