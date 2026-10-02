-- [NEW-DESIGN] 이 랩을 위해 새로 정한 스키마. FLO의 실제 테이블/컬럼명이 아니다.
--
-- 의도적으로 "레거시" 상태로 시작한다: PK 외에 다른 인덱스·제약이 전혀 없다.
-- 유통사ID+기간+track_id 조합이 업무상 유니크하다는 것은 facts에서 확인된
-- 사실이지만(2026-09-27), 그 제약이 실제로 DB 레벨에서 강제되고 있었는지는
-- 확인되지 않았다 — 그래서 이 랩에서는 "레거시 테이블에는 PK 외의 인덱스나
-- 제약이 없었다"는 쪽을 베이스라인으로 잡는다. 대량 삭제 시나리오
-- (deletion 패키지)의 각 테스트가 이 베이스라인 위에서 스스로
-- 유니크 제약/성능 인덱스를 추가하며 "무엇이 왜 개선됐는지"를 코드로 보여준다.
CREATE TABLE equity_share (
    id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    distributor_code  VARCHAR(20)    NOT NULL,
    track_id          BIGINT UNSIGNED NOT NULL,
    start_date        DATE           NOT NULL,
    end_date          DATE           NOT NULL,
    share_percentage  DECIMAL(5, 2)  NOT NULL,
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
