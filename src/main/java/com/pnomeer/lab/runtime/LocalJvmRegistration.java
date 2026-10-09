package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.ExperimentScenario;
import com.pnomeer.lab.worker.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.*;

/** Server-owned deployment binding. Never constructed from a scenario or HTTP input. */
public record LocalJvmRegistration(String id, WorkerManifest manifest, Path workspaces, Path storageRoot,
        Set<String> metricKeys, Function<ExperimentScenario, LocalJvmExecutor.Request> requests,
        Supplier<Readiness> readiness, List<String> variants) {
    public record Readiness(String state, String reason) {
        public boolean ready() { return state.equals("READY"); }
    }
    public LocalJvmRegistration {
        Objects.requireNonNull(manifest); Objects.requireNonNull(readiness); Objects.requireNonNull(requests);
        metricKeys = Set.copyOf(metricKeys); variants = List.copyOf(variants);
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Worker registration ID");
    }
    ExperimentExecutor executor() {
        return new LocalJvmExecutor(manifest, WorkerProcess.Limits.DEFAULT, workspaces, metricKeys, requests,
                new WorkerArtifactImporter(storageRoot, WorkerArtifactImporter.Limits.DEFAULT)) {
            @Override public void preflight() {
                var result = readiness.get();
                if (!result.ready()) throw new WorkerUnavailableException(result.state());
            }
        };
    }
    public static final class WorkerUnavailableException extends IllegalStateException {
        public WorkerUnavailableException(String state) { super(state); }
    }
}
