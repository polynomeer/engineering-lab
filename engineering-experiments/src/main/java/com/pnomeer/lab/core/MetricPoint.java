package com.pnomeer.lab.core;

import java.util.Map;

public record MetricPoint(
        long timestampEpochMs,
        Map<String, Double> gauges,
        Map<String, Long> counters,
        Map<String, String> labels) {

    public MetricPoint {
        gauges = Map.copyOf(gauges == null ? Map.of() : gauges);
        counters = Map.copyOf(counters == null ? Map.of() : counters);
        labels = Map.copyOf(labels == null ? Map.of() : labels);
    }
}
