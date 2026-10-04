package com.portfolio.mdslab.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.portfolio.mdslab.AbstractMySqlIntegrationTest;
import com.portfolio.mdslab.domain.MessageStatus;
import com.portfolio.mdslab.domain.ProcessedMessage;
import com.portfolio.mdslab.repository.AlbumRepository;
import com.portfolio.mdslab.repository.ProcessedMessageRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * facts/projects/mds-global-distribution.md 2번 항목 중 멱등 처리 메커니즘을
 * 재현한다. "메시지 ID에 DB 유니크 제약을 걸어 중복 처리를 막는 방식"(2026-09-09
 * 확인)이 실제로 (1) 순차적 중복 전달, (2) 동시 중복 전달, (3) 실패 후 재시도
 * 세 가지 상황에서 의도한 대로 동작하는지를 증명한다.
 */
@DisplayName("이벤트 기반 대량 처리의 멱등 처리 — 메시지 ID DB 유니크 제약")
class AlbumRegistrationIdempotencyTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private AlbumRegistrationListener listener;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private ProcessedMessageRepository processedMessageRepository;

    @Test
    @DisplayName("같은 메시지 ID로 두 번(순차) 처리를 시도해도 실제 등록 로직은 한 번만 수행된다")
    void duplicateDeliverySequential_processesOnlyOnce() {
        String messageId = UUID.randomUUID().toString();
        AlbumRegisteredEvent event = new AlbumRegisteredEvent(messageId, "Midnight Echoes");

        listener.onMessage(event);
        listener.onMessage(event); // 중복 전달(at-least-once) 재현

        List<?> albums = albumRepository.findByTitle("Midnight Echoes");
        assertThat(albums)
                .as("멱등 처리가 없었다면 앨범이 2건 등록됐을 것이다")
                .hasSize(1);

        ProcessedMessage processed = processedMessageRepository.findById(messageId).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(MessageStatus.DONE);
        assertThat(processed.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 메시지 ID가 동시에(경쟁 상태로) 두 번 전달돼도 DB 유니크 제약이 한쪽만 통과시킨다")
    void duplicateDeliveryConcurrent_onlyOneWinnerProcesses() throws InterruptedException {
        String messageId = UUID.randomUUID().toString();
        AlbumRegisteredEvent event = new AlbumRegisteredEvent(messageId, "Concurrent Echoes");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger unexpectedFailures = new AtomicInteger();

        Runnable task = () -> {
            ready.countDown();
            try {
                go.await();
                listener.onMessage(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                unexpectedFailures.incrementAndGet();
            }
        };

        executor.submit(task);
        executor.submit(task);
        ready.await();
        go.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpectedFailures.get()).isZero();
        assertThat(albumRepository.findByTitle("Concurrent Echoes"))
                .as("동시에 도착한 중복 메시지라도 실제 등록은 정확히 한 번만 일어나야 한다")
                .hasSize(1);
    }

    @Test
    @DisplayName("처리에 실패한 메시지는 같은 ID로 재전달되면 다시 처리를 시도한다 (영구 스킵되지 않는다)")
    void failedMessage_isRetriedOnRedelivery() {
        String messageId = UUID.randomUUID().toString();
        AlbumRegisteredEvent failingEvent =
                new AlbumRegisteredEvent(messageId, AlbumRegisteredEvent.FORCE_FAIL_TITLE);

        assertThatThrownBy(() -> listener.onMessage(failingEvent))
                .isInstanceOf(IllegalStateException.class);

        ProcessedMessage afterFirstAttempt = processedMessageRepository.findById(messageId).orElseThrow();
        assertThat(afterFirstAttempt.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(afterFirstAttempt.getAttemptCount()).isEqualTo(1);

        // 실제 SQS라면 가시성 타임아웃 후 같은 메시지를 재전달한다. 이번에는 (같은 messageId,
        // 정상 페이로드로) 재시도가 성공하는 상황을 재현한다.
        AlbumRegisteredEvent retryEvent = new AlbumRegisteredEvent(messageId, "Recovered Echoes");
        assertDoesNotThrow(() -> listener.onMessage(retryEvent));

        ProcessedMessage afterRetry = processedMessageRepository.findById(messageId).orElseThrow();
        assertThat(afterRetry.getStatus()).isEqualTo(MessageStatus.DONE);
        assertThat(afterRetry.getAttemptCount())
                .as("재시도 시 attempt_count가 증가해야 한다")
                .isEqualTo(2);
        assertThat(albumRepository.findByTitle("Recovered Echoes")).hasSize(1);
    }

    @Test
    @DisplayName("이미 DONE 처리된 메시지는 재전달돼도 다시 시도하지 않는다 (FAILED만 재시도 허용)")
    void doneMessage_isNeverRetried() {
        String messageId = UUID.randomUUID().toString();
        listener.onMessage(new AlbumRegisteredEvent(messageId, "Already Done"));

        // 같은 ID로 다른 페이로드가 재전달돼도(실제로는 같은 메시지가 재전달되는 것이지만,
        // 여기서는 "만약 재처리됐다면 새 앨범이 또 생겼을 것"을 증명하기 위해 다른 제목을 쓴다).
        listener.onMessage(new AlbumRegisteredEvent(messageId, "Should Not Register"));

        assertThat(albumRepository.findByTitle("Should Not Register")).isEmpty();
        assertThat(albumRepository.findByTitle("Already Done")).hasSize(1);

        ProcessedMessage processed = processedMessageRepository.findById(messageId).orElseThrow();
        assertThat(processed.getAttemptCount()).isEqualTo(1);
    }
}
