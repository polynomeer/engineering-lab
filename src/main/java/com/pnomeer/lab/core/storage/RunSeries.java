package com.pnomeer.lab.core.storage;

import java.util.Objects;

/** One scenario executed repeatedly; warmup runs precede measured repetitions. */
public record RunSeries(long sequence, String seriesId, String experimentType, String scenarioId, String definitionSha256,
        int repetitions, int warmup, RunStatus status, long createdAtEpochMs, long completedAtEpochMs, String failureMessage) {
    public static final int MAX_REPETITIONS = 50;
    public static final int MAX_WARMUP = 10;
    public RunSeries {
        Objects.requireNonNull(seriesId); Objects.requireNonNull(experimentType); Objects.requireNonNull(scenarioId);
        Objects.requireNonNull(definitionSha256); Objects.requireNonNull(status);
        if (repetitions < 1 || repetitions > MAX_REPETITIONS) throw new IllegalArgumentException("repetitions must be 1.." + MAX_REPETITIONS);
        if (warmup < 0 || warmup > MAX_WARMUP) throw new IllegalArgumentException("warmup must be 0.." + MAX_WARMUP);
    }
    public int total() { return repetitions + warmup; }
    public record Member(int position, String runId, boolean warmup) {
        public Member { Objects.requireNonNull(runId); if (position < 0) throw new IllegalArgumentException("position"); }
    }
}
