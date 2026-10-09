package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import java.util.Map;
import java.util.function.Consumer;

/** Execution boundary; admission, lifecycle and durable evidence remain with the runner. */
interface ExperimentExecutor {
    Map<String, String> parameters();
    default void preflight() {}

    /**
     * Stop observation producers, register their artifacts, drain metrics, then finalize logs.
     * Drain must run during cleanup even on execution failure. Clear thread interruption
     * during cleanup and report it in the outcome; the runner restores it after completion.
     */
    <C extends ExperimentScenario> Outcome execute(Experiment<C> experiment, C scenario, Output output);

    /** Streaming sinks only: executors cannot create, start or complete durable runs. */
    record Output(String runId, Consumer<MetricPoint> observationMetrics,
                  Consumer<MetricPoint> experimentMetrics, ExecutionEvidence evidence,
                  Consumer<ArtifactReference> artifacts, Runnable drainMetrics, Consumer<RunExecution.Phase> phase,
                  Consumer<RunExecution.Worker> worker, Consumer<RunCheck> checks) {
        public Output(String runId, Consumer<MetricPoint> observationMetrics, Consumer<MetricPoint> experimentMetrics,
                ExecutionEvidence evidence, Consumer<ArtifactReference> artifacts, Runnable drainMetrics,
                Consumer<RunExecution.Phase> phase) {
            this(runId, observationMetrics, experimentMetrics, evidence, artifacts, drainMetrics, phase,
                    ignored -> { throw new IllegalStateException("Worker evidence unavailable"); },
                    ignored -> { throw new IllegalStateException("Check evidence unavailable"); });
        }
        public Output(String runId, Consumer<MetricPoint> observationMetrics, Consumer<MetricPoint> experimentMetrics,
                ExecutionEvidence evidence, Consumer<ArtifactReference> artifacts, Runnable drainMetrics) {
            this(runId, observationMetrics, experimentMetrics, evidence, artifacts, drainMetrics, ignored -> {});
        }
    }

    record Outcome(RunStatus status, ExperimentSummary summary, String failure, boolean interrupted) {}
}
