package com.pnomeer.lab.core;

import java.util.Map;

public record ExperimentSummary(
        Map<String, Long> counters,
        Map<String, Double> gauges,
        Map<String, String> details) {

    private static final ExperimentSummary EMPTY = new ExperimentSummary(Map.of(), Map.of(), Map.of());

    public ExperimentSummary {
        counters = Map.copyOf(counters == null ? Map.of() : counters);
        gauges = Map.copyOf(gauges == null ? Map.of() : gauges);
        details = Map.copyOf(details == null ? Map.of() : details);
    }

    public static ExperimentSummary empty() {
        return EMPTY;
    }
}
