package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.worker.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Explicit container deployment; normal completion and startup recovery share the v2 guard. */
public record ContainerWorkerRegistration(String id, ContainerWorkerManifest manifest, Path workspaces, Path storageRoot,
        Set<String> metricKeys, Function<ExperimentScenario, LocalJvmExecutor.Request> requests,
        ContainerWorkerResources resources, Supplier<LocalJvmRegistration.Readiness> readiness, List<String> variants) implements WorkerRegistration {
    public ContainerWorkerRegistration {
        if (id == null || !id.matches("[a-zA-Z0-9._-]{1,100}")) throw new IllegalArgumentException("Worker registration ID");
        Objects.requireNonNull(manifest); Objects.requireNonNull(resources); Objects.requireNonNull(requests);
        if (resources.profile().equals("unrecoverable")) throw new IllegalArgumentException("Container resource recovery required");
        metricKeys = Set.copyOf(metricKeys); variants = List.copyOf(variants);
        var delegate = Objects.requireNonNull(readiness);
        var guard = new ContainerRecoveryGuard(workspaces, id);
        var legacy = new WorkerRecoveryGuard(LocalJvmRegistration.marker(workspaces, id));
        readiness = () -> guard.pending() || legacy.pending()
                ? new LocalJvmRegistration.Readiness("INCOMPATIBLE", "WORKER_ACTIVE_OR_RECOVERY_REQUIRED") : delegate.get();
    }
    public boolean producesMetrics() { return !metricKeys.isEmpty(); }
    public String moduleId() { return manifest.moduleId(); }
    public Set<String> experiments() { return manifest.experiments(); }
    public RunExecution.Kind kind() { return RunExecution.Kind.CONTAINER; }
    public ExperimentExecutor executor() {
        var guard = new ContainerRecoveryGuard(workspaces, id);
        var process = new ContainerWorkerProcess(manifest, WorkerProcess.Limits.DEFAULT, resources);
        return new LocalJvmExecutor("CONTAINER", moduleId(), manifest.artifactSha256(), WorkerProcess.Limits.DEFAULT,
                workspaces, metricKeys, requests, new WorkerArtifactImporter(storageRoot, WorkerArtifactImporter.Limits.DEFAULT),
                (command, root, sink) -> {
                    // The complete command is validated before the admission record or workspace is created.
                    WorkerProtocol.writeCommand(java.io.OutputStream.nullOutputStream(), command, ProtocolLimits.DEFAULT);
                    try (var ticket = guard.acquire()) {
                        Path workspace = ticket.begin(command, manifest, resources.profile());
                        var result = process.executeWorkspace(command, workspace, sink);
                        ticket.release(); // Returned transport result already confirms all resource cleanup.
                        return result;
                    }
                }) {
            @Override public void preflight() {
                var state = readiness.get();
                if (!state.ready()) throw new LocalJvmRegistration.WorkerUnavailableException(state.state());
            }
        };
    }
    public WorkerStartupRecovery.Result recover(RunStore store) { return recover(store, DockerContainerEngine::new); }
    WorkerStartupRecovery.Result recover(RunStore store, Function<String, WorkerContainerLifecycle.Engine> engines) {
        var guard = new ContainerRecoveryGuard(workspaces, id);
        if (!guard.pending()) return WorkerStartupRecovery.Result.NOT_REQUIRED;
        try (var ticket = guard.acquire()) {
            var active = ticket.load(manifest, resources.profile());
            String runId = active.path("runId").asText();
            var stored = store.find(runId);
            var execution = store.execution(runId);
            if (stored.isEmpty() || stored.get().completedAtEpochMs() <= 0
                    || !Set.of(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.INTERRUPTED).contains(stored.get().status())
                    || !stored.get().experimentType().equals(active.path("experimentId").asText())
                    || execution.isEmpty() || execution.get().definition().kind() != RunExecution.Kind.CONTAINER
                    || execution.get().definition().protocolVersion() != 1
                    || !execution.get().definition().moduleId().equals(moduleId())
                    || execution.get().worker() != null && !manifest.artifactSha256().equals(execution.get().worker().artifactSha256())) return WorkerStartupRecovery.Result.REQUIRES_OPERATOR;
            Path workspace = ticket.workspace();
            if (!Files.notExists(workspace, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) return WorkerStartupRecovery.Result.REQUIRES_OPERATOR;
                var recovery = resources.recovery(workspace, runId, manifest.artifactSha256());
                Path journal = workspace.resolve("container-lifecycle");
                if (!Files.notExists(journal, LinkOption.NOFOLLOW_LINKS)) {
                    var spec = new WorkerContainerLifecycle.Spec(runId, manifest.artifactSha256(), manifest.imageId(), List.of("sleep", "900"));
                    try (var worker = new WorkerContainerLifecycle(journal, spec, engines.apply(recovery.networkId()))) {
                        if (worker.reconcile() == WorkerContainerLifecycle.Recovery.REQUIRES_OPERATOR) return WorkerStartupRecovery.Result.REQUIRES_OPERATOR;
                    }
                }
                recovery.finish();
            }
            if (Thread.currentThread().isInterrupted()) return WorkerStartupRecovery.Result.FAILED;
            ticket.release();
            return WorkerStartupRecovery.Result.RECOVERED;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return WorkerStartupRecovery.Result.FAILED; }
        catch (Exception e) { return WorkerStartupRecovery.Result.REQUIRES_OPERATOR; }
    }
}
