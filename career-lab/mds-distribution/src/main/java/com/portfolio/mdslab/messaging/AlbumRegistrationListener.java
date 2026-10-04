package com.portfolio.mdslab.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * [신규 결정] design 5.3절의 {@code @SqsListener} 리스너를 실제 SQS 없이
 * 재현한 것. 이름·시그니처는 SQS가 메시지를 전달했을 때 호출될 핸들러를
 * 그대로 흉내내지만, 실제로는 테스트(또는 향후 실제 SQS 연동 시 그 어댑터)가
 * {@link #onMessage}를 직접 호출한다 — "메시지가 최소 한 번(at-least-once)
 * 중복 전달될 수 있다"는 전제만 결정론적으로 재현하면 되므로, 큐잉 자체를
 * 흉내내는 것보다 이 편이 시간 대비 가치가 높다고 판단했다(README 참고).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlbumRegistrationListener {

    private final IdempotentMessageProcessor idempotency;
    private final AlbumRegistrationService albumRegistrationService;

    /** SQS 워커가 메시지를 수신했을 때 호출될 핸들러에 해당. */
    public void onMessage(AlbumRegisteredEvent event) {
        if (!idempotency.tryBegin(event.messageId(), "ALBUM_REGISTRATION")) {
            log.info("이미 처리됐거나 처리 중인 메시지, 건너뜀: {}", event.messageId());
            return; // 실제 SQS라면 여기서 정상 ACK(메시지 삭제)에 해당
        }
        try {
            albumRegistrationService.process(event);
            idempotency.markDone(event.messageId());
        } catch (RuntimeException e) {
            idempotency.markFailed(event.messageId());
            throw e; // 실제 SQS라면 재던짐 → 가시성 타임아웃 후 재전달, maxReceiveCount 초과 시 DLQ
        }
    }
}
