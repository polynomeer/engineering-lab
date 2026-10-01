package com.pnomeer.lab.core;

public interface ExperimentRunner {
    <C extends ExperimentScenario> ExperimentResult run(Experiment<C> experiment, C scenario);
}
