package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import java.util.Map;
import java.util.function.Consumer;

/** Execution boundary; admission, lifecycle and durable evidence remain with the runner. */
interface ExperimentExecutor {
    Map<String, String> parameters();
    RunEnvironment environment();

    /**
     * Stop observation producers, register their artifacts, drain metrics, then finalize logs.
     * Drain must run during cleanup even on execution failure. Clear thread interruption
     * during cleanup and report it in the outcome; the runner restores it after completion.
     */
    <C extends ExperimentScenario> Outcome execute(Experiment<C> experiment, C scenario, Output output);

    /** Streaming sinks only: executors cannot create, start or complete durable runs. */
    record Output(String runId, Consumer<MetricPoint> observationMetrics,
                  Consumer<MetricPoint> experimentMetrics, ExecutionEvidence evidence,
                  Consumer<ArtifactReference> artifacts, Runnable drainMetrics) {}

    record Outcome(RunStatus status, ExperimentSummary summary, String failure, boolean interrupted) {}
}
