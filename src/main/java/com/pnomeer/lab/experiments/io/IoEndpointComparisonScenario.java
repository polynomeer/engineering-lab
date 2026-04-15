package com.pnomeer.lab.experiments.io;

import com.pnomeer.lab.core.ExperimentScenario;

import java.util.Map;

public record IoEndpointComparisonScenario(
        String scenarioId,
        int requests,
        long delayMs,
        boolean pinning,
        long pinDelayMs) implements ExperimentScenario {

    public IoEndpointComparisonScenario {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId must not be blank");
        }
        if (requests <= 0) {
            throw new IllegalArgumentException("requests must be positive");
        }
        if (delayMs < 0L) {
            throw new IllegalArgumentException("delayMs must not be negative");
        }
        if (pinDelayMs < 0L) {
            throw new IllegalArgumentException("pinDelayMs must not be negative");
        }
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of(
                "requests", String.valueOf(requests),
                "delayMs", String.valueOf(delayMs),
                "pinning", String.valueOf(pinning),
                "pinDelayMs", String.valueOf(pinDelayMs));
    }
}
