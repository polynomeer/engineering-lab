package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.RunStatus;
import java.io.IOException;
import com.pnomeer.lab.worker.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.*;

/** Server-owned deployment binding. Never constructed from a scenario or HTTP input. */
public record LocalJvmRegistration(String id, WorkerManifest manifest, Path workspaces, Path storageRoot,
        Set<String> metricKeys, Function<ExperimentScenario, LocalJvmExecutor.Request> requests,
        Supplier<Readiness> readiness, List<String> variants) implements WorkerRegistration {
    public record Readiness(String state, String reason) {
        public boolean ready() { return state.equals("READY"); }
    }
    public LocalJvmRegistration {
        Objects.requireNonNull(manifest); Objects.requireNonNull(readiness); Objects.requireNonNull(requests);
        metricKeys = Set.copyOf(metricKeys); variants = List.copyOf(variants);
        if (id == null || !id.matches("[a-zA-Z0-9._-]{1,100}")) throw new IllegalArgumentException("Worker registration ID");
        var guard = new WorkerRecoveryGuard(marker(workspaces, id));
        var delegate = readiness;
        readiness = () -> guard.pending() || new ContainerRecoveryGuard(workspaces, id).pending()
                ? new Readiness("INCOMPATIBLE", "WORKER_ACTIVE_OR_RECOVERY_REQUIRED") : delegate.get();
    }
    static Path marker(Path workspaces, String id) { return workspaces.resolve(".active-" + id + ".properties"); }
    public String moduleId() { return manifest.moduleId(); }
    public Set<String> experiments() { return manifest.experiments(); }
    public com.pnomeer.lab.core.storage.RunExecution.Kind kind() { return com.pnomeer.lab.core.storage.RunExecution.Kind.LOCAL_JVM; }
    public ExperimentExecutor executor() {
        var guard = new WorkerRecoveryGuard(marker(workspaces, id));
        return new LocalJvmExecutor(manifest, WorkerProcess.Limits.DEFAULT, workspaces, metricKeys, requests,
                new WorkerArtifactImporter(storageRoot, WorkerArtifactImporter.Limits.DEFAULT)) {
            @Override public <C extends ExperimentScenario> Outcome execute(Experiment<C> experiment, C scenario, Output output) {
                WorkerRecoveryGuard.Ticket ticket;
                try {
                    if (new ContainerRecoveryGuard(workspaces, id).pending()) throw new IOException("Container recovery required");
                    ticket = guard.begin(output.runId(), manifest.artifactSha256());
                }
                catch (IOException e) {
                    return new Outcome(RunStatus.FAILED, ExperimentSummary.empty(), "WORKER_RECOVERY_REQUIRED", false);
                }
                // Exceptions, interruption and controller death intentionally retain the marker.
                var outcome = super.executeWithCompletion(experiment, scenario, output, result ->
                        ticket.recordCompletion(result.workspace(), result.outcome().path("status").asText(),
                                result.outcome().path("reason").asText("")));
                if (outcome.status() == RunStatus.SUCCEEDED && !outcome.interrupted()) {
                    try { ticket.confirmSuccess(); }
                    catch (IOException e) {
                        return new Outcome(RunStatus.FAILED, outcome.summary(), "WORKER_RECOVERY_RELEASE_FAILED", false);
                    }
                }
                return outcome;
            }
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
