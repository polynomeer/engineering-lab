package com.pnomeer.lab.core;

import java.util.List;

public record ExperimentResult(
        String experimentType,
        String scenarioId,
        ExperimentStatus status,
        long startedAtEpochMs,
        long completedAtEpochMs,
        ExperimentSummary summary,
        String failureMessage,
        List<MetricPoint> timeline) {

    public ExperimentResult {
        summary = summary == null ? ExperimentSummary.empty() : summary;
        timeline = List.copyOf(timeline == null ? List.of() : timeline);
    }
}
