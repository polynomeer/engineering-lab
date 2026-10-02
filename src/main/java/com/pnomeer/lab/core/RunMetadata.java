package com.pnomeer.lab.core;

import java.util.Map;
import java.util.Objects;

/** Immutable input and environment snapshot captured before experiment execution. */
public record RunMetadata(
        String runId,
        Map<String, String> parameters,
        RunEnvironment environment,
        GitProvenance git) {

    public RunMetadata(String runId, Map<String, String> parameters, RunEnvironment environment) {
        this(runId, parameters, environment, GitProvenance.unknown("Not configured"));
    }

    public RunMetadata {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(git, "git");
    }
}
