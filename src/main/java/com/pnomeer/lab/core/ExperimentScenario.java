package com.pnomeer.lab.core;

import java.util.Map;

public interface ExperimentScenario {
    String scenarioId();

    default String displayName() {
        return scenarioId();
    }

    default Map<String, String> parameters() {
        return Map.of();
    }
}
