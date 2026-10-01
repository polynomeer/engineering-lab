package com.pnomeer.lab.experiments.pipeline.model;

import java.util.Objects;

public final class MappedRow {
    private final int rowIndex;
    private final IngestItem item;

    public MappedRow(int rowIndex, IngestItem item) {
        if (rowIndex < 0) {
            throw new IllegalArgumentException("rowIndex must be >= 0");
        }
        this.rowIndex = rowIndex;
        this.item = Objects.requireNonNull(item, "item must not be null");
    }

    public int getRowIndex() {
        return rowIndex;
    }

    public IngestItem getItem() {
        return item;
    }
}
