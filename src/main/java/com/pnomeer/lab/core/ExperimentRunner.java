package com.pnomeer.lab.core;

import java.util.UUID;

public interface ExperimentRunner {
    default <C extends ExperimentScenario> ExperimentResult run(Experiment<C> experiment, C scenario) {
        return run(experiment, scenario, UUID.randomUUID().toString());
    }

    <C extends ExperimentScenario> ExperimentResult run(Experiment<C> experiment, C scenario, String runId);
}
