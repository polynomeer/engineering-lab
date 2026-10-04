package com.pnomeer.lab.registry;

import com.pnomeer.lab.core.input.InputPayload;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.scenario.*;
import java.util.function.*;

public record ExperimentRegistration<C extends ExperimentScenario>(ExperimentDescriptor descriptor,
        Function<ScenarioDefinition, Experiment<C>> experimentFactory,
        BiFunction<ScenarioDefinition, InputPayload, C> scenarioFactory) {
    public ExperimentInvocation<C> bind(ScenarioDefinition definition, InputPayload input) {
        descriptor.validate(definition);
        if (definition.input() == null) {
            if (input != null) throw new ScenarioException("This experiment does not accept a file");
        } else if (input == null || input.size() == 0 || !definition.input().sha256().equals(input.sha256())) {
            throw new ScenarioException("Missing input or input SHA-256 mismatch");
        }
        var experiment = experimentFactory.apply(definition);
        if (!experiment.type().equals(descriptor.id())) throw new IllegalStateException("Registry factory type mismatch");
        return new ExperimentInvocation<>(experiment, scenarioFactory.apply(definition, input));
    }
}
