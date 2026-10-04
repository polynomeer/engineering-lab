package com.pnomeer.lab.core;

import java.util.List;
import java.util.Objects;

/** A rejected source row; rowIndex is zero-based within the first worksheet. */
public record DeadLetter(int rowIndex, String stage, String reason, List<String> cells) {
    public static final int MAX_BYTES = 65536;
    public static final int MAX_PAGE = 100;
    public DeadLetter {
        if (rowIndex < 0) throw new IllegalArgumentException("rowIndex must be nonnegative");
        if (!"validation".equals(stage)) throw new IllegalArgumentException("Unsupported dead-letter stage");
        Objects.requireNonNull(reason);
        if (reason.isBlank() || reason.length() > 1000) throw new IllegalArgumentException("Invalid dead-letter reason");
        if (cells.size() > 16384) throw new IllegalArgumentException("Too many source cells");
        long characters = reason.length();
        for (String cell : cells) characters += Objects.requireNonNull(cell).length();
        if (characters > MAX_BYTES) throw new IllegalArgumentException("Dead-letter row exceeds character limit");
        cells = List.copyOf(cells);
    }
}
