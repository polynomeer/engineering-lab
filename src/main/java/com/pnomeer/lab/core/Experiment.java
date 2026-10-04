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

    default ExperimentSummary execute(C scenario, Consumer<MetricPoint> timelineListener,
            TraceRecorder trace, String parentSpanId, ExecutionEvidence evidence) throws Exception {
        return execute(scenario, timelineListener, trace, parentSpanId);
    }

    default ExperimentSummary execute(C scenario, Consumer<MetricPoint> timelineListener,
            TraceRecorder trace, String parentSpanId) throws Exception {
        return execute(scenario, timelineListener);
    }
}
