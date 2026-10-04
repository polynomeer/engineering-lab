package com.portfolio.creatorlab.onboarding;

/**
 * [FACT 기반 재현] design/creator-studio.md 4.3절 {@code GET /creators/me} 응답
 * 스키마 — 이 판정이 틀리면 재가입 오판(4번 장애)으로 이어지는 핵심 지점이다.
 */
public record CreatorStatusView(boolean joined, Long creatorId, Long producerId, String displayName) {

    public static CreatorStatusView notJoined() {
        return new CreatorStatusView(false, null, null, null);
    }

    public static CreatorStatusView active(Long creatorId, Long producerId, String displayName) {
        return new CreatorStatusView(true, creatorId, producerId, displayName);
    }
}
