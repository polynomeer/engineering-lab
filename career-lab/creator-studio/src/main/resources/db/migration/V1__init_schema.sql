-- [FACT 기반 재현] design/creator-studio.md 3.3절 DDL을 이 랩이 실제로 다루는
-- 범위(시나리오 2: Creator-Producer 매핑 무결성, 시나리오 3: Post-Commit 외부연동)에
-- 맞춰 가져온 것. KMS 암호화(email_enc 등)는 [NEW-DESIGN]이지만 이 랩에서는
-- 실제 AWS KMS 연동 없이 평문 컬럼으로 단순화했다 — 이 랩이 증명하려는 것은
-- 암호화 구현이 아니라 Creator-Producer 매핑 무결성과 세션 구조이기 때문이다.
CREATE TABLE member (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    email      VARCHAR(255) NOT NULL COMMENT '[NEW-DESIGN 단순화] 실제로는 KMS 봉투암호화 대상(design 8절) - 이 랩에서는 평문 저장',
    status     ENUM('ACTIVE','WITHDRAWN') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_member_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE producer (
    id                        BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name                      VARCHAR(100) NOT NULL,
    representative_creator_id BIGINT UNSIGNED NULL,
    status                    ENUM('ACTIVE','SUSPENDED') NOT NULL DEFAULT 'ACTIVE',
    created_at                DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- [FACT 기반 재현] uq_creator_member: "member당 Creator는 최초 가입 시 1개까지만"
-- 이라는 design 3.1절의 신규 결정 — 4번 장애(Creator-Producer 매핑 장애) 재발 방지의
-- DB 레벨 최후 방어선이다.
CREATE TABLE creator (
    id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    member_id    BIGINT UNSIGNED NOT NULL,
    producer_id  BIGINT UNSIGNED NOT NULL,
    display_name VARCHAR(60) NOT NULL,
    status       ENUM('ACTIVE','WITHDRAWN') NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_creator_member (member_id),
    KEY idx_creator_producer (producer_id),
    CONSTRAINT fk_creator_member   FOREIGN KEY (member_id)   REFERENCES member(id),
    CONSTRAINT fk_creator_producer FOREIGN KEY (producer_id) REFERENCES producer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE producer
    ADD CONSTRAINT fk_producer_rep_creator
        FOREIGN KEY (representative_creator_id) REFERENCES creator(id);

CREATE TABLE program (
    id                    BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    producer_id           BIGINT UNSIGNED NOT NULL,
    created_by_creator_id BIGINT UNSIGNED NOT NULL,
    title                 VARCHAR(150) NOT NULL,
    status                ENUM('DRAFT','PUBLISHED','ARCHIVED') NOT NULL DEFAULT 'DRAFT',
    created_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_program_producer (producer_id),
    CONSTRAINT fk_program_producer FOREIGN KEY (producer_id) REFERENCES producer(id),
    CONSTRAINT fk_program_creator  FOREIGN KEY (created_by_creator_id) REFERENCES creator(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- [FACT 기반 재현] design 5.5절 — Producer 매핑 변경은 반드시 이 이력 테이블에
-- 기록되고, 일반 가입 흐름과 별개의 관리자 API를 통해서만 발생한다.
CREATE TABLE producer_mapping_history (
    id                   BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    creator_id           BIGINT UNSIGNED NOT NULL,
    from_producer_id     BIGINT UNSIGNED NULL,
    to_producer_id       BIGINT UNSIGNED NOT NULL,
    changed_by_member_id BIGINT UNSIGNED NOT NULL,
    reason               VARCHAR(255) NOT NULL,
    created_at           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_pmh_creator FOREIGN KEY (creator_id) REFERENCES creator(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
