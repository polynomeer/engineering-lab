package com.pnomeer.lab.registry;

import com.pnomeer.lab.core.input.InputPayload;

import com.pnomeer.lab.scenario.*;
import java.util.*;

public final class ExperimentRegistry {
    private final Map<String, ExperimentRegistration<?>> entries;
    public ExperimentRegistry(List<ExperimentRegistration<?>> registrations) {
        var entries = new TreeMap<String, ExperimentRegistration<?>>();
        for (var registration : registrations) {
            if (entries.putIfAbsent(registration.descriptor().id(), registration) != null) {
                throw new IllegalArgumentException("Duplicate experiment ID: " + registration.descriptor().id());
            }
        }
        this.entries = Collections.unmodifiableMap(entries);
    }
    public List<ExperimentDescriptor> list() { return entries.values().stream().map(ExperimentRegistration::descriptor).toList(); }
    public ExperimentDescriptor get(String id) { return entry(id).descriptor(); }
    public void validate(ScenarioDefinition definition) { get(definition.experiment()).validate(definition); }
    public ExperimentInvocation<?> bind(ScenarioDefinition definition, InputPayload input, ScenarioDocuments documents) {
        return entry(definition.experiment()).bind(definition, input).withDefinition(definition, documents);
    }
    private ExperimentRegistration<?> entry(String id) {
        var registration = entries.get(id);
        if (registration == null) throw new ScenarioException("Unknown experiment: " + id);
        return registration;
    }
}
