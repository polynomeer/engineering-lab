package com.portfolio.creatorlab.onboarding;

/**
 * [FACT 기반 재현] design/creator-studio.md 5.5절 / 8절 — 기존 Producer에 콘텐츠
 * (Program)가 있는데 명시적인 transferContent 확인 없이 producer_id를 바꾸려는
 * 시도를 차단한다. facts 4번 섹션 가설 ②("Producer 생성이 기존 Creator의
 * producer_id를 덮어썼을 가능성")의 재발 방지 장치다.
 */
public class ContentTransferRequiredException extends RuntimeException {
    public ContentTransferRequiredException(Long producerId) {
        super("콘텐츠가 있는 Producer(id=" + producerId + ") 매핑 변경은 transferContent 확인이 필요합니다.");
    }
}
