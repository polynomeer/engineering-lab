package com.portfolio.creatorlab.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.creatorlab.AbstractMySqlIntegrationTest;
import com.portfolio.creatorlab.domain.Creator;
import com.portfolio.creatorlab.domain.Member;
import com.portfolio.creatorlab.domain.Producer;
import com.portfolio.creatorlab.domain.Program;
import com.portfolio.creatorlab.repository.CreatorRepository;
import com.portfolio.creatorlab.repository.MemberRepository;
import com.portfolio.creatorlab.repository.ProducerMappingHistoryRepository;
import com.portfolio.creatorlab.repository.ProducerRepository;
import com.portfolio.creatorlab.repository.ProgramRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * facts/projects/creator-studio.md 4번 섹션(Creator-Producer 매핑 장애)의 재발
 * 방지 방향을 재현한다. 4번 섹션의 원인 분석은 가설 단계이지 확정 사실이 아니다 —
 * 이 테스트는 "만약 정말 그런 구조였다면 어떻게 재현되고, 재발 방지 구조는 그걸
 * 어떻게 막는가"를 결정론적으로 보여주는 것이지, 실제 FLO 장애의 정확한 재현이
 * 아니다(design/creator-studio.md 1.2절과 동일한 관점).
 */
@DisplayName("Creator-Producer 매핑 무결성 — legacy(무조건 덮어쓰기) vs 분리된 관리자 API")
class CreatorProducerMappingIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private ProducerRepository producerRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private ProgramRepository programRepository;
    @Autowired
    private ProducerMappingHistoryRepository historyRepository;
    @Autowired
    private LegacyProducerCreationService legacyProducerCreationService;
    @Autowired
    private CreatorOnboardingService creatorOnboardingService;
    @Autowired
    private ProducerMappingAdminService producerMappingAdminService;

    @Test
    @DisplayName("[Before, 가설 재현] 기존 Creator가 있어도 Producer 생성 로직이 producer_id를 무조건 덮어써 콘텐츠가 사라진 것처럼 보인다")
    void legacyProducerCreationOverwritesExistingMapping() {
        Member member = memberRepository.save(new Member("legacy-bug@example.com"));
        Producer originalProducer = producerRepository.save(new Producer("원래 채널"));
        Creator creator = creatorRepository.save(new Creator(member.getId(), originalProducer.getId(), "홍길동"));
        Program originalProgram = programRepository.save(
                new Program(originalProducer.getId(), creator.getId(), "매일 아침 뉴스 브리핑"));

        // BUG 재현: 이미 Creator가 있는 memberId로 "Producer 생성" 경로를 다시 태운다
        // (facts 4번 섹션 가설 ① — 미가입 오판으로 재가입 플로우가 타는 상황을 흉내낸 입력).
        Creator remapped = legacyProducerCreationService.createProducerAndRemapCreator(
                member.getId(), "새로 만들어진 채널", "홍길동");

        assertThat(remapped.getId()).isEqualTo(creator.getId());
        assertThat(remapped.getProducerId())
                .as("BUG: 같은 Creator인데 producer_id가 새 Producer로 덮어써졌다")
                .isNotEqualTo(originalProducer.getId());

        // 원래 Program은 DB에 그대로 남아있지만, Creator는 더 이상 원래 Producer를
        // 가리키지 않으므로 "내 채널의 콘텐츠 목록"에서는 보이지 않는다 —
        // facts가 말하는 "그동안 등록한 프로그램·에피소드·클립이 전부 사라진 것처럼 보였다"와 동일한 결과.
        assertThat(programRepository.findByProducerId(originalProducer.getId()))
                .as("원래 콘텐츠 자체는 삭제되지 않았다 — 다만 접근 경로(producer_id)가 끊겼을 뿐")
                .hasSize(1)
                .first()
                .satisfies(p -> assertThat(p.getId()).isEqualTo(originalProgram.getId()));
        assertThat(remapped.getProducerId()).isNotEqualTo(originalProgram.getProducerId());
    }

    @Test
    @DisplayName("[After] joinAsNewCreator는 이미 Creator가 있으면 새 Producer를 만들지 않고 즉시 거절한다")
    void joinAsNewCreatorRejectsWhenCreatorAlreadyExists() {
        Member member = memberRepository.save(new Member("already-exists@example.com"));
        Creator existing = creatorOnboardingService.joinAsNewCreator(member.getId(), "첫 채널", "홍길동");

        assertThatThrownBy(() -> creatorOnboardingService.joinAsNewCreator(member.getId(), "또 다른 채널", "홍길동"))
                .isInstanceOf(CreatorAlreadyExistsException.class);

        // 재가입 시도가 거절됐으므로 원래 매핑은 전혀 건드려지지 않았다.
        Creator reloaded = creatorRepository.findById(existing.getId()).orElseThrow();
        assertThat(reloaded.getProducerId()).isEqualTo(existing.getProducerId());
    }

    @Test
    @DisplayName("[After] 콘텐츠가 있는 Producer는 transferContent 확인 없이는 매핑 변경이 차단된다")
    void changeProducerBlocksWhenContentExistsWithoutExplicitTransfer() {
        Member member = memberRepository.save(new Member("transfer-required@example.com"));
        Creator creator = creatorOnboardingService.joinAsNewCreator(member.getId(), "채널A", "홍길동");
        programRepository.save(new Program(creator.getProducerId(), creator.getId(), "에피소드가 있는 프로그램"));
        Producer targetProducer = producerRepository.save(new Producer("이관 대상 채널"));

        assertThatThrownBy(() -> producerMappingAdminService.changeProducer(
                creator.getId(), targetProducer.getId(), member.getId(), "테스트 이관", false))
                .isInstanceOf(ContentTransferRequiredException.class);

        Creator reloaded = creatorRepository.findById(creator.getId()).orElseThrow();
        assertThat(reloaded.getProducerId())
                .as("차단됐으므로 매핑은 그대로여야 한다")
                .isEqualTo(creator.getProducerId());
    }

    @Test
    @DisplayName("[After] transferContent=true로 명시하면 매핑이 변경되고 이력이 남는다")
    void changeProducerSucceedsWithExplicitTransferAndRecordsHistory() {
        Member member = memberRepository.save(new Member("transfer-ok@example.com"));
        Creator creator = creatorOnboardingService.joinAsNewCreator(member.getId(), "채널B", "김철수");
        Long fromProducerId = creator.getProducerId();
        programRepository.save(new Program(fromProducerId, creator.getId(), "프로그램"));
        Producer targetProducer = producerRepository.save(new Producer("VoC 복구용 채널"));

        producerMappingAdminService.changeProducer(
                creator.getId(), targetProducer.getId(), member.getId(), "VoC #1234 복구", true);

        Creator reloaded = creatorRepository.findById(creator.getId()).orElseThrow();
        assertThat(reloaded.getProducerId()).isEqualTo(targetProducer.getId());

        assertThat(historyRepository.findByCreatorId(creator.getId()))
                .as("매핑 변경 이력이 남아야 한다 — design 7.3절, DML 우회 대신 정식 관리자 기능으로 만든 이유")
                .anySatisfy(h -> {
                    assertThat(h.getFromProducerId()).isEqualTo(fromProducerId);
                    assertThat(h.getToProducerId()).isEqualTo(targetProducer.getId());
                    assertThat(h.getReason()).isEqualTo("VoC #1234 복구");
                });
    }
}
