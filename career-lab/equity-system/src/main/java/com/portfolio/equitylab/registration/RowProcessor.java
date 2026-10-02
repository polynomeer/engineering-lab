package com.portfolio.equitylab.registration;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * [FACT 기반 재현] facts/projects/equity-system.md 3번 항목의 "파싱–검증–
 * 매핑" 단계를 한 곳에 모은다.
 *
 * [NEW-DESIGN] 실제 Excel 파싱·검증 로직 대신, 그 작업이 가진 "행 하나당
 * 일정 시간이 걸리는 특성"(형식 검사, 외부 규칙 대조 등으로 인한 지연)을
 * Thread.sleep으로 흉내 낸다 — CPU 클럭에 의존하는 바쁜 대기(busy loop)
 * 대신 sleep을 쓴 이유는, 그래야 "여러 스레드가 각자의 대기 시간을 겹쳐
 * 쓸 수 있다"는 이 시나리오의 핵심(I/O 대기 시간 김 → 병렬화로 겹쳐 쓰기)이
 * 하드웨어 성능과 무관하게 항상 재현되기 때문이다.
 */
@Component
public class RowProcessor {

    /** [NEW-DESIGN] 행 1건을 검증하는 데 걸린다고 가정하는 지연 시간. */
    static final long SIMULATED_VALIDATION_MILLIS = 2;

    public MappedEquityRow process(RawEquityRow raw) {
        simulateValidationLatency();

        BigDecimal sharePercentage = new BigDecimal(raw.sharePercentageRaw());
        if (sharePercentage.compareTo(BigDecimal.ZERO) < 0 || sharePercentage.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("지분율은 0~100 사이여야 합니다: " + sharePercentage);
        }
        if (!raw.startDate().isBefore(raw.endDate())) {
            throw new IllegalArgumentException("시작일은 종료일보다 앞서야 합니다: " + raw);
        }

        return new MappedEquityRow(raw.distributorCode(), raw.trackId(), raw.startDate(), raw.endDate(),
                sharePercentage);
    }

    private void simulateValidationLatency() {
        try {
            Thread.sleep(SIMULATED_VALIDATION_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("검증 대기 중 인터럽트됨", e);
        }
    }

    public record MappedEquityRow(String distributorCode, long trackId, java.time.LocalDate startDate,
                                   java.time.LocalDate endDate, BigDecimal sharePercentage) {
    }
}
