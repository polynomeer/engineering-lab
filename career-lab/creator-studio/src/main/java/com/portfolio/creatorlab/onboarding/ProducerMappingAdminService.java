package com.portfolio.creatorlab.onboarding;

import com.portfolio.creatorlab.domain.Creator;
import com.portfolio.creatorlab.domain.ProducerMappingHistory;
import com.portfolio.creatorlab.repository.CreatorRepository;
import com.portfolio.creatorlab.repository.ProducerMappingHistoryRepository;
import com.portfolio.creatorlab.repository.ProgramRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [FACT 기반 재현] design/creator-studio.md 5.5절 / 7.3절 — Producer 매핑 변경을
 * 위한 유일한 진입점. {@link CreatorOnboardingService}와는 완전히 분리된
 * 관리자 전용 경로이며, 일반 가입/재가입 흐름에서는 이 서비스가 절대 호출되지 않는다.
 *
 * 원본 4번 장애 당시 DML로 직접 producer_id를 되돌리는 대신 애플리케이션 정상
 * 흐름(운영 계정으로 임시 Creator 생성 후 재매핑)을 택했던 판단(facts 4번 섹션)을,
 * 이번에는 이력 기록과 콘텐츠 존재 여부 검증까지 포함한 정식 기능으로 만든다.
 */
@Service
@RequiredArgsConstructor
public class ProducerMappingAdminService {

    private final CreatorRepository creatorRepository;
    private final ProgramRepository programRepository;
    private final ProducerMappingHistoryRepository historyRepository;

    @Transactional
    public void changeProducer(long creatorId, long toProducerId, long changedByMemberId,
                                String reason, boolean transferContent) {
        Creator creator = creatorRepository.findById(creatorId)
                .orElseThrow(() -> new EntityNotFoundException("Creator not found: " + creatorId));
        Long fromProducerId = creator.getProducerId();

        boolean hasContent = programRepository.existsByProducerId(fromProducerId);
        if (hasContent && !transferContent) {
            // 기존 Producer에 콘텐츠가 있으면 명시적 확인 없이는 차단한다 —
            // 4번 장애("등록한 프로그램·에피소드·클립이 전부 사라진 것처럼 보였다")의
            // 재발 방지 핵심 지점.
            throw new ContentTransferRequiredException(fromProducerId);
        }

        creator.reassignProducer(toProducerId);
        historyRepository.save(
                new ProducerMappingHistory(creatorId, fromProducerId, toProducerId, changedByMemberId, reason));
    }
}
