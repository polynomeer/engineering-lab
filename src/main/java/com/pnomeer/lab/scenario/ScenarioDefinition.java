package com.pnomeer.lab.scenario;

import java.util.Map;

/** Validated, format-independent definition. Parameters are scalar values only. */
public record ScenarioDefinition(int schemaVersion, String experiment, Scenario scenario,
        Map<String, Object> parameters, Measurement measurement, Input input) {
    public ScenarioDefinition { parameters = Map.copyOf(parameters); }
    public record Scenario(String id, String hypothesis) { }
    public record Measurement(String mode) { }
    public record Input(String file, String sha256) { }
}
