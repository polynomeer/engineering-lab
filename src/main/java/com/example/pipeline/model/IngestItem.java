package com.example.pipeline.model;

import java.util.Objects;

public final class IngestItem {
    private final String col1;
    private final String col2;
    private final int col3Int;

    public IngestItem(String col1, String col2, int col3Int) {
        this.col1 = Objects.requireNonNull(col1, "col1 must not be null");
        this.col2 = Objects.requireNonNull(col2, "col2 must not be null");
        this.col3Int = col3Int;
    }

    public String getCol1() {
        return col1;
    }

    public String getCol2() {
        return col2;
    }

    public int getCol3Int() {
        return col3Int;
    }
}
