package com.pnomeer.lab.runtime;

import java.util.Map;
import java.util.Objects;

/** Immutable, server-owned routing. Scenario parameters cannot choose an executor. */
final class ExperimentExecutors {
    record Registration(ExperimentExecutor executor, int maxActiveRuns) {
        Registration {
            Objects.requireNonNull(executor);
            if (maxActiveRuns < 1) throw new IllegalArgumentException("Executor limit must be positive");
        }
    }
    record Binding(String executorId, boolean exclusive) {
        Binding { Objects.requireNonNull(executorId); }
    }
    record Selection(String executorId, ExperimentExecutor executor, int maxActiveRuns, boolean exclusive) {}

    private final String defaultExecutorId;
    private final Map<String, Registration> registrations;
    private final Map<String, Binding> bindings;

    ExperimentExecutors(String defaultExecutorId, Map<String, Registration> registrations,
            Map<String, Binding> bindings) {
        this.defaultExecutorId = Objects.requireNonNull(defaultExecutorId);
        this.registrations = Map.copyOf(registrations);
        this.bindings = Map.copyOf(bindings);
        if (registrations.keySet().stream().anyMatch(String::isBlank)
                || bindings.keySet().stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Executor and experiment IDs must not be blank");
        }
        requireRegistration(defaultExecutorId);
        this.bindings.values().forEach(binding -> requireRegistration(binding.executorId()));
    }

    static ExperimentExecutors inProcess(ExperimentExecutor executor, int maxActiveRuns) {
        return new ExperimentExecutors("IN_PROCESS",
                Map.of("IN_PROCESS", new Registration(executor, maxActiveRuns)), Map.of());
    }

    Selection select(String experimentType) {
        var binding = bindings.getOrDefault(experimentType, new Binding(defaultExecutorId, false));
        var registration = requireRegistration(binding.executorId());
        return new Selection(binding.executorId(), registration.executor(), registration.maxActiveRuns(), binding.exclusive());
    }

    private Registration requireRegistration(String id) {
        var registration = registrations.get(id);
        if (registration == null) throw new IllegalArgumentException("Unregistered executor: " + id);
        return registration;
    }
}
