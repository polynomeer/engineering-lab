package com.portfolio.creatorlab.session;

/**
 * [NEW-DESIGN 단순화] design/creator-studio.md 4.3절의 access/refresh token 응답
 * 스키마를 그대로 가져오되, 토큰 자체는 서명·클레임을 가진 실제 JWT가 아니라
 * 무작위 opaque 문자열(UUID)로 단순화했다. 이 랩이 증명하려는 것은 JWT 서명
 * 검증이 아니라 "Redis 세션 키 구조가 refresh 이후 이전 토큰을 즉시 무효화할
 * 수 있는가"이기 때문이다.
 */
public record TokenPair(String accessToken, String refreshToken) {
}
