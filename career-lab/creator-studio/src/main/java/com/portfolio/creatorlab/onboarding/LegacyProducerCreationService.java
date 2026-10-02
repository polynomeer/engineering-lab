package com.portfolio.creatorlab.onboarding;

import com.portfolio.creatorlab.domain.Creator;
import com.portfolio.creatorlab.domain.Producer;
import com.portfolio.creatorlab.repository.CreatorRepository;
import com.portfolio.creatorlab.repository.ProducerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [FACT 기반 재현] "Before" — facts/projects/creator-studio.md 4번 섹션의 원인
 * 분석은 아직 가설 단계이고 확정되지 않았다는 점을 분명히 해둔다 — 실제 로그·DB
 * 조회로 원인을 확정하지 못했다고 facts에 명시되어 있다.
 *
 * 이 클래스는 그 두 가설 중 ②("Producer 생성 기능 자체가 신규 Producer INSERT 후
 * 기존 Creator의 producer_id를 신규 값으로 덮어썼을 가능성")를, "만약 정말 그런
 * 구조였다면 어떤 코드였을까"를 재구성한 것이다 — 실제 FLO 코드가 아니며,
 * 확정된 원인이 아니라 재현 가능한 버그 메커니즘 하나를 보여주기 위한 대조군이다.
 */
@Service
@RequiredArgsConstructor
public class LegacyProducerCreationService {

    private final CreatorRepository creatorRepository;
    private final ProducerRepository producerRepository;

    /**
     * BUG(가설 재현): 이미 Creator가 있어도 무조건 새 Producer를 만들고, 기존
     * Creator의 producer_id를 새 Producer로 무조건 덮어쓴다. 재가입 오판(가설 ①)이든
     * 직접 호출이든, 이 메서드가 호출되는 순간 기존 매핑은 복구 불가능하게 끊긴다.
     */
    @Transactional
    public Creator createProducerAndRemapCreator(long memberId, String producerName, String displayName) {
        Producer newProducer = producerRepository.save(new Producer(producerName));

        Creator existing = creatorRepository.findByMemberId(memberId).orElse(null);
        if (existing != null) {
            // BUG: 기존 Creator가 있는데도 producer_id를 새 값으로 그냥 덮어쓴다 —
            // 이전 Producer 밑에 있던 Program들은 더 이상 이 Creator와 연결되어
            // 조회되지 않는다(실제로 삭제되지는 않지만, Creator 입장에서는 "콘텐츠가
            // 전부 사라진 것처럼 보인다"는 facts의 증상과 동일한 결과).
            existing.reassignProducer(newProducer.getId());
            return existing;
        }
        return creatorRepository.save(new Creator(memberId, newProducer.getId(), displayName));
    }
}
