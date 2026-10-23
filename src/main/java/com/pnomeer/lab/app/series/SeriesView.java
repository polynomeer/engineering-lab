package com.pnomeer.lab.app.series;

import com.pnomeer.lab.core.storage.RunSeries;
import com.pnomeer.lab.core.storage.RunStatus;
import java.util.List;

/**
 * Series state with per-measure statistics over succeeded, non-warmup repetitions only. {@code overlappingRunIds}
 * lists other runs that executed while a repetition ran; their load is part of these numbers.
 */
public record SeriesView(RunSeries series, List<Member> members, int measuredSucceeded, List<Measure> measures,
        List<String> overlappingRunIds) {
    public record Member(int position, String runId, boolean warmup, RunStatus status, long startedAtEpochMs, long completedAtEpochMs) { }
    public record Measure(String role, String name, String unit, String kind, SeriesStatistics statistics) { }
}
