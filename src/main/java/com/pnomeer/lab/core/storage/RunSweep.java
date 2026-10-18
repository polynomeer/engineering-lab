package com.pnomeer.lab.core.storage;

import java.util.Map;
import java.util.Objects;

/** One series per parameter combination; {@code grid} keeps the requested values in order. */
public record RunSweep(long sequence, String sweepId, String experimentType, String scenarioId, Map<String, java.util.List<Object>> grid,
        int repetitions, int warmup, RunStatus status, long createdAtEpochMs, long completedAtEpochMs, String failureMessage) {
    public static final int MAX_POINTS = 24;
    public static final int MAX_RUNS = 300;
    public RunSweep {
        Objects.requireNonNull(sweepId); Objects.requireNonNull(experimentType); Objects.requireNonNull(scenarioId);
        Objects.requireNonNull(status); grid = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(grid));
    }
    public record Point(int position, String seriesId, Map<String, Object> parameters) {
        public Point { Objects.requireNonNull(seriesId); parameters = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(parameters)); }
    }
}
