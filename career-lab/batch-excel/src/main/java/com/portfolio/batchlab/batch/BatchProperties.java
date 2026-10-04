package com.portfolio.batchlab.batch;

/**
 * [NEW-DESIGN] career-hub design/batch-excel-optimization.md 8장 "재구현을 위해
 * 새로 정한 값" 표를 그대로 상수로 옮긴 것이다. facts에는 실제 FLO의 chunk
 * 건수·flush 주기가 "확인 필요"로 남아 있어(기억나지 않음), 이 프로젝트를 위해
 * 독립적으로 정했다 — 실제 FLO 값이 아니다.
 */
public final class BatchProperties {

    /** design 8장: chunk size 1,000건. flush()/clear() 주기도 동일 값으로 맞춘다(5.3절). */
    public static final int CHUNK_SIZE = 1_000;

    /** design 8장: Excel 생성용 조회 페이지 크기 2,000건. */
    public static final int EXCEL_QUERY_CHUNK_SIZE = 2_000;

    /** design 8장: SXSSF rowAccessWindowSize 500행. */
    public static final int SXSSF_WINDOW_SIZE = 500;

    /** [NEW-DESIGN] 레거시 Tasklet에서 DB round-trip을 줄이기 위한 내부 조회 페이지 크기.
     * chunk 처리와 달리 이 값 자체는 "영속성 컨텍스트를 비우는 단위"가 아니다 —
     * 레거시 시나리오는 의도적으로 트랜잭션이 끝날 때까지 flush/clear를 하지 않는다. */
    public static final int LEGACY_FETCH_PAGE_SIZE = 5_000;

    private BatchProperties() {
    }
}
