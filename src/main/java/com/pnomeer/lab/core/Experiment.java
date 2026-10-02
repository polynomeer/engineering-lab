package com.pnomeer.lab.core;

import java.util.function.Consumer;

public interface Experiment<C extends ExperimentScenario> {
    String type();

    default String displayName() {
        return type();
    }

    default java.util.Map<String, String> parameters(C scenario) {
        return scenario.parameters();
    }

    ExperimentSummary execute(C scenario, Consumer<MetricPoint> timelineListener) throws Exception;
}
