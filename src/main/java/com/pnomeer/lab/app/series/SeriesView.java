package com.pnomeer.lab.app.series;

import com.pnomeer.lab.core.storage.RunSeries;
import com.pnomeer.lab.core.storage.RunStatus;
import java.util.List;

/**
 * Series state with per-measure statistics over succeeded, non-warmup repetitions only. {@code overlappingRunIds}
 * lists other runs that executed while a repetition ran; their load is part of these numbers.
 */
public record SeriesView(RunSeries series, List<Member> members, int measuredSucceeded, List<Measure> measures,
        List<String> overlappingRunIds, Stability stability) {
    public record Member(int position, String runId, boolean warmup, RunStatus status, long startedAtEpochMs, long completedAtEpochMs) { }
    /**
     * Measures whose coefficient of variation exceeds the threshold (with at least three measured runs), and
     * the host 1-minute load range recorded at the start and end of the measured runs.
     */
    public record Stability(double variationThreshold, List<String> highVariationRoles, Double load1mMin, Double load1mMax,
            Integer availableProcessors) {
        public static final double HIGH_VARIATION = 0.10;
    }
    public record Measure(String role, String name, String unit, String kind, SeriesStatistics statistics) { }
}
