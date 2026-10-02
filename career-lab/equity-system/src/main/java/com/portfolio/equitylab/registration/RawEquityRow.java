package com.portfolio.equitylab.registration;

import java.time.LocalDate;

/**
 * [NEW-DESIGN] "Excel에서 막 읽은 한 줄"을 흉내 낸 원시 입력 — 아직 파싱·
 * 검증되지 않은 문자열 형태의 지분율 값을 담는다.
 */
public record RawEquityRow(String distributorCode, long trackId, LocalDate startDate, LocalDate endDate,
                            String sharePercentageRaw) {
}
