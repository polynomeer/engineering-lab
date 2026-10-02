package com.pnomeer.lab.registry;

import com.pnomeer.lab.scenario.*;
import java.util.*;

public record ExperimentDescriptor(String id, String title, String category, String input,
        Map<String, ParameterSpec> parameters, List<String> measurementModes) {
    public ExperimentDescriptor { parameters = Collections.unmodifiableMap(new TreeMap<>(parameters)); measurementModes = List.copyOf(measurementModes); }
    public void validate(ScenarioDefinition definition) {
        if (!id.equals(definition.experiment())) throw new ScenarioException("Experiment definition mismatch");
        if (!parameters.keySet().equals(definition.parameters().keySet())) {
            throw new ScenarioException("Required parameters for " + id + ": " + parameters.keySet());
        }
        parameters.forEach((key, spec) -> spec.validate(key, definition.parameters().get(key)));
        if (input.equals("XLSX_UPLOAD") != (definition.input() != null)) throw new ScenarioException("Unexpected or missing input for " + id);
    }
}
