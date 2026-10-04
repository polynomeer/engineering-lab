package com.portfolio.creatorlab.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.portfolio.creatorlab.AbstractMySqlIntegrationTest;
import com.portfolio.creatorlab.domain.Creator;
import com.portfolio.creatorlab.domain.Member;
import com.portfolio.creatorlab.domain.Producer;
import com.portfolio.creatorlab.repository.CreatorRepository;
import com.portfolio.creatorlab.repository.MemberRepository;
import com.portfolio.creatorlab.repository.ProducerRepository;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * [시나리오 3, 선택] facts/projects/creator-studio.md 1번 섹션 — "도메인 로직은
 * 이벤트 발행만 담당, 외부 호출은 커밋 이후 비동기 처리"가 실제로 그렇게 동작하는지
 * 증명한다: 트랜잭션이 롤백되면 AFTER_COMMIT 리스너는 전혀 실행되지 않고, 커밋되면
 * Braze/Mixpanel 양쪽 모두 독립적으로 호출된다.
 */
@DisplayName("Post-Commit 외부 연동 — AFTER_COMMIT에서만 실행되고 롤백 시에는 실행되지 않는다")
@Import(PostCommitEventListenerTest.RecordingClientsConfig.class)
class PostCommitEventListenerTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private ProducerRepository producerRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private ProgramCreationService programCreationService;
    @Autowired
    private RecordingBrazeClient brazeClient;
    @Autowired
    private RecordingMixpanelClient mixpanelClient;

    private Long producerId;
    private Long creatorId;

    @BeforeEach
    void setUpCreator() {
        brazeClient.calls.clear();
        mixpanelClient.calls.clear();
        Member member = memberRepository.save(new Member("post-commit-" + System.nanoTime() + "@example.com"));
        Producer producer = producerRepository.save(new Producer("포스트커밋 테스트 채널"));
        Creator creator = creatorRepository.save(new Creator(member.getId(), producer.getId(), "테스터"));
        this.producerId = producer.getId();
        this.creatorId = creator.getId();
    }

    @Test
    @DisplayName("커밋되면 Braze/Mixpanel 리스너가 각각 독립적으로 실행된다")
    void listenersRunAfterCommit() {
        programCreationService.createProgram(producerId, creatorId, "커밋되는 프로그램");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(brazeClient.calls).hasSize(1);
            assertThat(mixpanelClient.calls).hasSize(1);
        });
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 AFTER_COMMIT 리스너는 실행되지 않는다")
    void listenersDoNotRunWhenTransactionRollsBack() {
        try {
            programCreationService.createProgramThenRollback(producerId, creatorId, "롤백되는 프로그램");
        } catch (RuntimeException expected) {
            // 의도된 롤백 — ProgramCreationService.createProgramThenRollback 참고.
        }

        // 비동기 리스너가 "혹시라도" 실행될 시간을 충분히 준 뒤에도 호출이 없어야 한다.
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> {
                    assertThat(brazeClient.calls).isEmpty();
                    assertThat(mixpanelClient.calls).isEmpty();
                });
    }

    @TestConfiguration
    static class RecordingClientsConfig {

        @Bean
        @Primary
        RecordingBrazeClient recordingBrazeClient() {
            return new RecordingBrazeClient();
        }

        @Bean
        @Primary
        RecordingMixpanelClient recordingMixpanelClient() {
            return new RecordingMixpanelClient();
        }
    }

    static class RecordingBrazeClient implements BrazeClient {
        final List<ContentChangedEvent> calls = new CopyOnWriteArrayList<>();

        @Override
        public void track(ContentChangedEvent event) {
            calls.add(event);
        }
    }

    static class RecordingMixpanelClient implements MixpanelClient {
        final List<ContentChangedEvent> calls = new CopyOnWriteArrayList<>();

        @Override
        public void track(ContentChangedEvent event) {
            calls.add(event);
        }
    }
}
