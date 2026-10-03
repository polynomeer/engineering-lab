package com.pnomeer.lab.registry;

import com.pnomeer.lab.scenario.*;
import java.util.*;

public record ExperimentDescriptor(String id, String title, String category, String input,
        Map<String, ParameterSpec> parameters, List<String> measurementModes) {
    public ExperimentDescriptor { parameters = Collections.unmodifiableMap(new TreeMap<>(parameters)); measurementModes = List.copyOf(measurementModes); }
    public void validate(ScenarioDefinition definition) {
        if (!id.equals(definition.experiment())) throw new ScenarioException("Experiment definition mismatch");
        if (!parameters.keySet().containsAll(definition.parameters().keySet())
                || parameters.entrySet().stream().anyMatch(e -> e.getValue().defaultValue() == null && !definition.parameters().containsKey(e.getKey()))) {
            throw new ScenarioException("Unknown or missing required parameters for " + id + "; allowed: " + parameters.keySet());
        }
        parameters.forEach((key, spec) -> spec.validate(key, definition.parameters().getOrDefault(key, spec.defaultValue())));
        if (input.equals("XLSX_UPLOAD") != (definition.input() != null)) throw new ScenarioException("Unexpected or missing input for " + id);
    }
}
