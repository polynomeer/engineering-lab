package com.portfolio.creatorlab.onboarding;

import com.portfolio.creatorlab.domain.Creator;
import com.portfolio.creatorlab.domain.Producer;
import com.portfolio.creatorlab.repository.CreatorRepository;
import com.portfolio.creatorlab.repository.ProducerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [FACT 기반 재현] design/creator-studio.md 5.5절 — facts/projects/creator-studio.md
 * 4번 섹션의 "재발 방지 방향(제안)"을 그대로 구현한다. Producer 생성과 기존 Creator의
 * Producer 변경을 완전히 분리된 진입점으로 나눈다 — 이 서비스는 오직 "최초 가입"만
 * 담당하고, 기존 Creator의 producer_id를 바꾸는 어떤 메서드도 갖지 않는다
 * (그 역할은 {@link ProducerMappingAdminService}가 전담).
 */
@Service
@RequiredArgsConstructor
public class CreatorOnboardingService {

    private final CreatorRepository creatorRepository;
    private final ProducerRepository producerRepository;

    /**
     * GET /creators/me 전용 — 절대 상태를 변경하지 않는다(읽기 전용).
     * facts 4번 섹션 가설 ①(가입 여부 오판)을 재현하지 않도록, 캐시를 경유하지 않고
     * 매 호출마다 DB를 직접 조회한다.
     */
    @Transactional(readOnly = true)
    public CreatorStatusView getStatus(long memberId) {
        return creatorRepository.findByMemberId(memberId)
                .map(c -> CreatorStatusView.active(c.getId(), c.getProducerId(), c.getDisplayName()))
                .orElse(CreatorStatusView.notJoined());
    }

    /**
     * 최초 가입 전용. 이미 Creator가 있으면 새 Producer를 만들지 않고 즉시 거절한다.
     * {@code uq_creator_member} 유니크 제약이 최후 방어선이지만, 애플리케이션
     * 레벨에서도 먼저 명시적으로 막아 "미가입으로 오판해 새 Producer를 만드는"
     * 4번 장애를 재현하지 않는다.
     */
    @Transactional
    public Creator joinAsNewCreator(long memberId, String producerName, String displayName) {
        if (creatorRepository.existsByMemberId(memberId)) {
            throw new CreatorAlreadyExistsException(memberId);
        }
        Producer producer = producerRepository.save(new Producer(producerName));
        Creator creator = creatorRepository.save(new Creator(memberId, producer.getId(), displayName));
        producer.assignRepresentative(creator.getId());
        return creator;
    }
}
