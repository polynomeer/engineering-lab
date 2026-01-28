package com.pnomeer.pipeline.model;

import java.util.Objects;

public final class ValidationError {
    private final int rowIndex;
    private final String reason;
    private final RawRow rawSnapshot;

    public ValidationError(int rowIndex, String reason, RawRow rawSnapshot) {
        if (rowIndex < 0) {
            throw new IllegalArgumentException("rowIndex must be >= 0");
        }
        this.rowIndex = rowIndex;
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.rawSnapshot = Objects.requireNonNull(rawSnapshot, "rawSnapshot must not be null");
    }

    public int getRowIndex() {
        return rowIndex;
    }

    public String getReason() {
        return reason;
    }

    public RawRow getRawSnapshot() {
        return rawSnapshot;
    }
}
