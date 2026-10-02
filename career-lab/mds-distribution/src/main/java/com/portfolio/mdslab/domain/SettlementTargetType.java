package com.portfolio.mdslab.domain;

/**
 * [사실 근거] design/mds-global-distribution.md 5.2절 — 정산 락의 대상 타입.
 * 이 랩에서는 ALBUM만 실제로 사용하지만(CONTRACT 엔티티는 시연 범위 밖),
 * 원 설계의 폴리모픽 구조(target_type + target_id)를 그대로 보존하기 위해
 * enum 자체는 두 값을 유지한다.
 */
public enum SettlementTargetType {
    CONTRACT,
    ALBUM
}
