-- [FACT 기반 재현] album은 "정산 중 메타데이터 수정 차단" 시나리오의 가드 대상.
-- design/mds-global-distribution.md 3.3절 DDL을 이 랩의 축소 범위(F-4/F-5 시연에
-- 필요한 최소 컬럼)로 줄인 것 — status/upc/contract_id 등은 이 랩의 시연 목적과
-- 무관해 생략했다.
CREATE TABLE album (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    title           VARCHAR(300) NOT NULL,
    release_date    DATE NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- [NEW-DESIGN] 스키마 자체는 design/mds-global-distribution.md 3.3절과 동일 —
-- facts에는 "상태 플래그 기반"이라는 개념만 있고 컬럼 정의는 없었으므로 재구현 시
-- 새로 정한 것이다. target_type/target_id로 대상을 폴리모픽하게 참조한다(FK 없음).
CREATE TABLE settlement_lock (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_type     VARCHAR(30) NOT NULL,
    target_id       BIGINT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'UNLOCKED',
    locked_at       DATETIME NULL,
    expires_at      DATETIME NULL,
    locked_by       VARCHAR(100) NULL,
    version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_settlement_lock_target UNIQUE (target_type, target_id)
);

-- [NEW-DESIGN] 멱등 처리 스키마 — facts에는 "메시지 ID에 DB 유니크 제약"이라는
-- 메커니즘만 확인되고 테이블/컬럼은 없었다. message_id를 PK로 둬서 그 자체가
-- 유니크 제약이 되도록 한다. status에 FAILED를 허용해 "실패한 메시지는 같은
-- ID로 재시도할 수 있어야 한다"는 요구를 스키마 레벨에서 지원한다.
CREATE TABLE processed_message (
    message_id      VARCHAR(100) PRIMARY KEY,
    message_type    VARCHAR(50) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    attempt_count   INT NOT NULL DEFAULT 1,
    processed_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
