package com.portfolio.creatorlab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [NEW-DESIGN] design/creator-studio.md 3.1절 — 원본의 "FLO 계정 → Character"를
 * 이 독립 프로젝트의 자체 회원(member)으로 대체한 것. FLO 계정 스키마가 아니다.
 *
 * [NEW-DESIGN 단순화] design 3.3절 DDL은 email을 KMS 봉투암호화 대상으로 두지만,
 * 이 랩은 실제 AWS KMS를 연동하지 않고 평문 컬럼으로 단순화했다 — 이 랩이 증명하려는
 * 핵심은 암호화 구현이 아니라 Creator-Producer 매핑 무결성과 세션 구조이기 때문이다.
 * facts에는 "KMS로 암호화한 대상 필드"가 확인 필요로 남아있었다.
 */
@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MemberStatus status = MemberStatus.ACTIVE;

    public Member(String email) {
        this.email = email;
        this.status = MemberStatus.ACTIVE;
    }

    public enum MemberStatus {
        ACTIVE, WITHDRAWN
    }
}
