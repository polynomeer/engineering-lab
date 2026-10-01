package com.portfolio.mdslab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * [NEW-DESIGN] 멱등 처리 테이블. facts에는 "메시지 ID에 DB 유니크 제약"이라는
 * 메커니즘만 확인되었고(2026-09-09), 정확한 스키마는 없었다. message_id를
 * PK(=유니크 제약)로 두는 것으로 그 메커니즘을 재구현한다.
 *
 * <p>{@link Persistable}을 구현해 {@link #isNew()}가 항상 true를 반환하게 한
 * 이유 — 이 랩을 만들며 직접 겪은 함정(README 참고): {@code @Id}를
 * {@code @GeneratedValue} 없이 애플리케이션이 직접 채우면, Spring Data JPA의
 * 기본 {@code isNew()} 판정("ID가 null이 아니면 새 엔티티가 아니다")에 걸려
 * {@code JpaRepository.save()}가 {@code persist()}가 아니라 {@code merge()}를
 * 호출해버린다. merge()는 "있으면 UPDATE, 없으면 INSERT"라서, 이미 처리된
 * (DONE/FAILED) 메시지를 같은 ID로 다시 저장하면 유니크 제약 위반 예외 없이
 * 조용히 기존 행을 덮어써 버려 — 멱등성 체크 자체가 무력화된다. isNew()를
 * 항상 true로 고정하면 save()가 항상 persist()(=INSERT 시도)를 호출하도록
 * 강제되어, 이미 존재하는 messageId에 대해서는 DB가 실제로 유니크 제약
 * 위반을 던지게 된다 — 이것이 이 멱등 처리 메커니즘이 성립하기 위한 전제다.
 */
@Entity
@Table(name = "processed_message")
@Getter
@NoArgsConstructor
public class ProcessedMessage implements Persistable<String> {

    @Id
    @Column(name = "message_id")
    private String messageId;

    @Column(name = "message_type", nullable = false)
    private String messageType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    public ProcessedMessage(String messageId, String messageType) {
        this.messageId = messageId;
        this.messageType = messageType;
        this.status = MessageStatus.PROCESSING;
        this.attemptCount = 1;
        this.processedAt = LocalDateTime.now();
    }

    @Override
    public String getId() {
        return messageId;
    }

    /** 항상 true — 클래스 주석 참고. save()가 항상 INSERT를 시도하도록 강제한다. */
    @Override
    @Transient
    public boolean isNew() {
        return true;
    }
}
