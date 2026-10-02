-- [FACT 기반 재현] contract_sequence 테이블 구조는
-- career-hub design/mcp-platform-revamp.md 3장 DDL을 그대로 따른다.
-- (facts/projects/mcp-platform-revamp.md "4. 시퀀스 구조 개선"의 range allocation을
-- 실제 테이블로 옮긴 것 — 원 FLO 스키마 자체는 접근 불가하므로 재구성.)
CREATE TABLE contract_sequence (
    seq_key        VARCHAR(50)  PRIMARY KEY,
    current_value  BIGINT       NOT NULL,
    block_size     INT          NOT NULL,
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- [NEW-DESIGN] block_size=500은 design/mcp-platform-revamp.md 8장에서 재구현을 위해
-- 새로 정한 값이다. 실제 FLO 운영값이 아니다.
INSERT INTO contract_sequence (seq_key, current_value, block_size)
VALUES ('CONTRACT_CODE', 0, 500);

-- [NEW-DESIGN] 계약 코드 채번·검증 파이프라인 시나리오를 실제로 저장까지 이어보기 위한
-- 최소 contract 테이블. design 문서의 album/track/equity 전체 스키마는 이 랩의 핵심
-- 검증 대상(채번 동시성, ArchUnit)이 아니므로 범위에서 제외했다.
CREATE TABLE contract (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_code      VARCHAR(30)   NOT NULL,
    counterparty_name  VARCHAR(200)  NOT NULL,
    starts_at          DATE          NOT NULL,
    expires_at         DATE          NOT NULL,
    created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_contract_code (contract_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
