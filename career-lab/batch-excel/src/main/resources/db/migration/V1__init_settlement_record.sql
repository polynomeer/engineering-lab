-- [NEW-DESIGN] career-hub design/batch-excel-optimization.md 3.1절 DDL 중
-- 이 랩의 두 시나리오(배치 메모리 안정성, Excel 스트리밍)에 실제로 필요한
-- settlement_record 테이블만 가져온다. member/subscription/settlement_batch/
-- excel_export_job(잡 상태 추적용 도메인 테이블)은 이 랩의 스코프(메모리 비교
-- 벤치마크)에는 필요하지 않아 생략했다 — REST API 계층을 만들지 않기 때문이다.
CREATE TABLE settlement_record (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    subscription_id       BIGINT NOT NULL,
    target_month          CHAR(7) NOT NULL,           -- 'yyyy-MM'
    gross_amount          INT NOT NULL,
    fee_amount            INT NOT NULL,
    net_amount            INT NOT NULL,
    settlement_status     VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING, AGGREGATED
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- design 문서 3.1절과 동일: keyset(커서) 페이지네이션의 정렬·필터 기준이 되는 복합 인덱스.
    INDEX idx_settlement_month_status_id (target_month, settlement_status, id)
) ENGINE=InnoDB;
