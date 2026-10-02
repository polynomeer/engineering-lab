package com.portfolio.creatorlab.integration;

import com.portfolio.creatorlab.domain.Program;
import com.portfolio.creatorlab.repository.ProgramRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [FACT 기반 재현] facts/projects/creator-studio.md 1번 섹션 — 도메인 서비스는
 * 이벤트만 발행하고 외부 호출은 모른다. {@code publishFailingProgram}은 "커밋이
 * 안 되면 AFTER_COMMIT 리스너도 실행되지 않는다"를 증명하기 위한 테스트 전용
 * 시나리오 메서드다([NEW-DESIGN] — 원본 facts에는 이런 실패 테스트가 없었다).
 */
@Service
@RequiredArgsConstructor
public class ProgramCreationService {

    private final ProgramRepository programRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Program createProgram(Long producerId, Long creatorId, String title) {
        Program program = programRepository.save(new Program(producerId, creatorId, title));
        eventPublisher.publishEvent(new ContentChangedEvent(ContentType.PROGRAM, program.getId(), ChangeType.CREATED));
        return program;
    }

    /**
     * [NEW-DESIGN] 저장과 이벤트 발행 이후 트랜잭션을 강제로 롤백시켜, 커밋되지
     * 않은 변경에 대해서는 AFTER_COMMIT 리스너가 전혀 실행되지 않음을 증명한다.
     */
    @Transactional
    public void createProgramThenRollback(Long producerId, Long creatorId, String title) {
        Program program = programRepository.save(new Program(producerId, creatorId, title));
        eventPublisher.publishEvent(new ContentChangedEvent(ContentType.PROGRAM, program.getId(), ChangeType.CREATED));
        throw new RuntimeException("의도적 롤백 — 테스트 전용");
    }
}
