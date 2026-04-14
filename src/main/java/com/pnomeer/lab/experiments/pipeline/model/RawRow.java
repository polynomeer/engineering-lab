package com.pnomeer.lab.experiments.pipeline.model;

import java.util.List;
import java.util.Objects;

public final class RawRow {
    private final int rowIndex;
    private final List<String> cells;

    public RawRow(int rowIndex, List<String> cells) {
        if (rowIndex < 0) {
            throw new IllegalArgumentException("rowIndex must be >= 0");
        }
        this.rowIndex = rowIndex;
        this.cells = List.copyOf(Objects.requireNonNull(cells, "cells must not be null"));
    }

    public int getRowIndex() {
        return rowIndex;
    }

    public List<String> getCells() {
        return cells;
    }
}
