package com.portfolio.creatorlab.onboarding;

/**
 * [FACT 기반 재현] design/creator-studio.md 5.5절 — 이미 Creator가 있는 member가
 * 다시 가입을 시도할 때 명시적으로 거절하기 위한 예외. facts 4번 섹션 가설 ①
 * ("가입 여부 조회가 기존 Creator를 미가입으로 오판해 재가입 플로우가 새 Producer를
 * 만들어버렸을 가능성")을 구조적으로 막는 장치다.
 */
public class CreatorAlreadyExistsException extends RuntimeException {
    public CreatorAlreadyExistsException(Long memberId) {
        super("이미 가입된 Creator가 있습니다. memberId=" + memberId);
    }
}
