package com.portfolio.mcplab.sequence;

/**
 * [NEW-DESIGN] 채번 연산 한 번이 확보한 [rangeStart, rangeEnd] 구간(양끝 포함).
 * 이 구간 안의 번호는 오직 이 호출을 수행한 인스턴스만 순서대로 소비해야 하며,
 * 다른 인스턴스가 확보한 구간과 절대 겹치면 안 된다 — 겹치면 계약 코드 중복 발급으로
 * 이어진다(facts "다중 인스턴스에서의 범위 충돌" 문제 그 자체).
 */
public record AllocatedRange(long rangeStart, long rangeEnd) {

    public AllocatedRange {
        if (rangeEnd < rangeStart) {
            throw new IllegalArgumentException("rangeEnd(%d) < rangeStart(%d)".formatted(rangeEnd, rangeStart));
        }
    }

    public long size() {
        return rangeEnd - rangeStart + 1;
    }

    public boolean overlaps(AllocatedRange other) {
        return this.rangeStart <= other.rangeEnd && other.rangeStart <= this.rangeEnd;
    }
}
