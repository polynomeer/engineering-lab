package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.ExperimentScenario;
import com.pnomeer.lab.worker.WorkerProcess;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Function;

/** Explicit server wiring only; no default catalog registration or legacy marker release. */
public final class ContainerWorkerExecutor extends LocalJvmExecutor {
    ContainerWorkerExecutor(ContainerWorkerManifest manifest, WorkerProcess.Limits limits, Path workspaces, Path storage,
                            Set<String> metrics, Function<ExperimentScenario, Request> requests) {
        this(manifest, limits, workspaces, storage, metrics, requests, ContainerWorkerResources.NONE);
    }
    public ContainerWorkerExecutor(ContainerWorkerManifest manifest, WorkerProcess.Limits limits, Path workspaces, Path storage,
                            Set<String> metrics, Function<ExperimentScenario, Request> requests, ContainerWorkerResources resources) {
        super("CONTAINER", manifest.moduleId(), manifest.artifactSha256(), limits, workspaces, metrics, requests,
                new WorkerArtifactImporter(storage, WorkerArtifactImporter.Limits.DEFAULT),
                new ContainerWorkerProcess(manifest, limits, resources)::execute);
    }
}
