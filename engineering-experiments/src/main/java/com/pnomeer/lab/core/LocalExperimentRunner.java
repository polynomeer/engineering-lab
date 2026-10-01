package com.pnomeer.lab.core;

import java.util.ArrayList;
import java.util.List;

public final class LocalExperimentRunner implements ExperimentRunner {
    @Override
    public <C extends ExperimentScenario> ExperimentResult run(Experiment<C> experiment, C scenario) {
        long startedAtEpochMs = System.currentTimeMillis();
        List<MetricPoint> timeline = new ArrayList<>();
        try {
            ExperimentSummary summary = experiment.execute(scenario, timeline::add);
            return new ExperimentResult(
                    experiment.type(),
                    scenario.scenarioId(),
                    ExperimentStatus.SUCCEEDED,
                    startedAtEpochMs,
                    System.currentTimeMillis(),
                    summary,
                    null,
                    timeline);
        } catch (Exception ex) {
            return new ExperimentResult(
                    experiment.type(),
                    scenario.scenarioId(),
                    ExperimentStatus.FAILED,
                    startedAtEpochMs,
                    System.currentTimeMillis(),
                    ExperimentSummary.empty(),
                    ex.getMessage() == null ? "Experiment failed" : ex.getMessage(),
                    timeline);
        }
    }
}
