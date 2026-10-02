package com.portfolio.creatorlab.session;

/**
 * [NEW-DESIGN 단순화] design/creator-studio.md 3.4절 세션 본체 value의 최소
 * 재구현 — 실제로는 {sub, creatorId, producerId, deviceId, roles, iat, exp} 전체를
 * JSON으로 담지만, 이 랩에서는 세션 조회 성패(있음/없음)가 핵심이므로 memberId와
 * deviceId만 남긴다.
 */
public record SessionData(long memberId, String deviceId) {

    public String serialize() {
        return memberId + "|" + deviceId;
    }

    public static SessionData deserialize(String raw) {
        String[] parts = raw.split("\\|", 2);
        return new SessionData(Long.parseLong(parts[0]), parts[1]);
    }
}
