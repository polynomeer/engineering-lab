package com.pnomeer.lab.registry;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.scenario.*;
import java.util.*;
import java.util.function.Consumer;

public record ExperimentInvocation<C extends ExperimentScenario>(Experiment<C> experiment, C scenario) {
    public ExperimentInvocation<C> withDefinition(ScenarioDefinition definition, ScenarioDocuments documents) {
        String canonical = documents.canonical(definition);
        String hash = documents.hash(definition);
        Experiment<C> delegate = experiment;
        return new ExperimentInvocation<>(new Experiment<>() {
            public String type() { return delegate.type(); }
            public String displayName() { return delegate.displayName(); }
            public Map<String, String> parameters(C input) {
                var parameters = new HashMap<>(delegate.parameters(input));
                parameters.put("scenario.definition", canonical);
                parameters.put("scenario.definition.sha256", hash);
                parameters.put("scenario.hypothesis", definition.scenario().hypothesis());
                parameters.put("measurement.mode", definition.measurement().mode());
                return Map.copyOf(parameters);
            }
            public ExperimentSummary execute(C input, Consumer<MetricPoint> sink, TraceRecorder trace, String parentSpanId) throws Exception {
                return delegate.execute(input, sink, trace, parentSpanId);
            }
            public ExperimentSummary execute(C input, Consumer<MetricPoint> sink) throws Exception {
                return delegate.execute(input, sink);
            }
        }, scenario);
    }
}
