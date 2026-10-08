package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.function.Supplier;

/** Executes and observes only the current JVM. It has no RunStore or lifecycle authority. */
final class InProcessExecutor implements ExperimentExecutor {
    private static final Logger log = LoggerFactory.getLogger(InProcessExecutor.class);
    private final JfrRecorder jfr;
    private final RunLogRecorder runLogs;
    private final boolean traceEnabled;
    private final int traceMaxSpans;
    private final Supplier<MetricPoint> jvmProbe;
    private final long jvmIntervalMs;
    private final int maxPeriodicSamples;
    private final long shutdownTimeoutMs;

    InProcessExecutor(Supplier<MetricPoint> jvmProbe, long jvmIntervalMs, int maxPeriodicSamples,
            boolean traceEnabled, int traceMaxSpans, JfrRecorder jfr, RunLogRecorder runLogs,
            long shutdownTimeoutMs) {
        this.runLogs = runLogs;
        this.jfr = jfr;
        new TraceProperties(traceEnabled, traceMaxSpans);
        this.traceEnabled=traceEnabled; this.traceMaxSpans=traceMaxSpans;
        new ObservationProperties(jvmProbe != null, jvmIntervalMs, maxPeriodicSamples);
        this.jvmIntervalMs = jvmIntervalMs; this.maxPeriodicSamples = maxPeriodicSamples;
        this.jvmProbe = jvmProbe;
        this.shutdownTimeoutMs = shutdownTimeoutMs;
    }

    @Override public Map<String, String> parameters() {
        var parameters = new HashMap<String, String>();
        parameters.put("observation.schema.version", com.pnomeer.lab.metrics.MetricCatalog.VERSION);
        parameters.put("observation.jvm.mode", jvmProbe == null ? "OFF" : jvmIntervalMs == 0 ? "BOUNDARY" : "PERIODIC");
        parameters.put("observation.jvm.intervalMs", String.valueOf(jvmProbe == null ? 0 : jvmIntervalMs));
        parameters.put("observation.jvm.maxPeriodicSamples", String.valueOf(jvmProbe == null || jvmIntervalMs == 0 ? 0 : maxPeriodicSamples));
        parameters.put("observation.trace.mode", traceEnabled ? "EXPLICIT" : "OFF");
        parameters.put("observation.trace.schemaVersion", "1");
        parameters.put("observation.trace.maxSpans", String.valueOf(traceEnabled ? traceMaxSpans : 0));
        parameters.putAll(jfr == null ? Map.of("observation.jfr.mode", "OFF") : jfr.parameters());
        parameters.putAll(runLogs == null ? Map.of("observation.logs.mode", "OFF") : runLogs.parameters());
        return Map.copyOf(parameters);
    }

    @Override public <C extends ExperimentScenario> Outcome execute(Experiment<C> experiment, C scenario,
            Output output) {
        var trace = new TraceRecorder(traceEnabled ? output.observationMetrics() : null, output.runId(), traceMaxSpans);
        JfrRecorder.Session recording = null;
        RunLogRecorder.Session runLog = null;
        var logReason = RunLogRecorder.Reason.NONE;
        var executionStage = RunLogRecorder.Stage.EXECUTION;
        ExperimentSummary summary = ExperimentSummary.empty();
        RunStatus status = RunStatus.SUCCEEDED;
        String failure = null;
        boolean interrupted = false;
        boolean measuring = false;
        var observation = new JvmObservationSession(jvmProbe, output.observationMetrics(), jvmIntervalMs,
                maxPeriodicSamples, shutdownTimeoutMs, output.runId());
        try {
            if (runLogs != null) runLog = runLogs.start(output.runId());
            executionStage = RunLogRecorder.Stage.JFR;
            if (jfr != null) recording = jfr.start(output.runId());
            executionStage = RunLogRecorder.Stage.JVM;
            observation.start();
            executionStage = RunLogRecorder.Stage.STORAGE;
            output.phase().accept(RunExecution.Phase.MEASURING);
            measuring = true;
            executionStage = RunLogRecorder.Stage.EXECUTION;
            summary = trace.call(TraceRecorder.Stage.EXPERIMENT, null, rootSpan -> experiment.execute(scenario, point -> {
                observation.checkFailure();
                output.experimentMetrics().accept(point);
            }, trace, rootSpan, output.evidence()));
            if (runLog != null) runLog.record(RunLogRecorder.Stage.EXECUTION, RunLogRecorder.Event.SUCCEEDED, RunLogRecorder.Reason.NONE);
        } catch (Exception | OutOfMemoryError ex) {
            boolean executionInterrupted = ex instanceof InterruptedException
                    || ex instanceof PartialExecutionFailure partial && partial.interrupted();
            if (ex instanceof PartialExecutionFailure partial) summary = partial.summary();
            status = executionInterrupted ? RunStatus.INTERRUPTED : RunStatus.FAILED;
            failure = ex instanceof OutOfMemoryError ? "Out of memory"
                    : ex.getMessage() == null ? "Experiment failed" : ex.getMessage();
            interrupted = Thread.interrupted() || executionInterrupted;
            logReason = executionInterrupted ? RunLogRecorder.Reason.INTERRUPTED
                    : ex instanceof OutOfMemoryError ? RunLogRecorder.Reason.OUT_OF_MEMORY
                    : switch (executionStage) {
                        case JFR -> RunLogRecorder.Reason.JFR_FAILED;
                        case JVM -> RunLogRecorder.Reason.JVM_FAILED;
                        case STORAGE -> RunLogRecorder.Reason.STORAGE_FAILED;
                        default -> RunLogRecorder.Reason.EXECUTION_FAILED;
                    };
            if (runLog != null) runLog.record(executionStage,
                    executionInterrupted ? RunLogRecorder.Event.INTERRUPTED : RunLogRecorder.Event.FAILED, logReason);
        } catch (Error ex) {
            logReason = RunLogRecorder.Reason.EXECUTION_FAILED;
            if (runLog != null) runLog.record(executionStage, RunLogRecorder.Event.FAILED, logReason);
            throw ex;
        } finally {
            try {
                if (measuring) {
                    try { output.phase().accept(RunExecution.Phase.FINALIZING); }
                    catch (RuntimeException | OutOfMemoryError ex) {
                        status = RunStatus.FAILED;
                        failure = "Execution phase persistence failed" + (failure == null ? "" : "; experiment: " + failure);
                        logReason = RunLogRecorder.Reason.STORAGE_FAILED;
                        if (runLog != null) runLog.record(RunLogRecorder.Stage.STORAGE, RunLogRecorder.Event.FAILED, logReason);
                        log.error("job_id={} stage=execution-evidence reason=phase_failed", output.runId(), ex);
                        interrupted |= Thread.interrupted();
                    }
                }
                trace.close();
                try { observation.finish(); }
                catch (RuntimeException | OutOfMemoryError ex) {
                    status = RunStatus.FAILED;
                    logReason = RunLogRecorder.Reason.JVM_FAILED;
                    if (runLog != null) runLog.record(RunLogRecorder.Stage.JVM, RunLogRecorder.Event.FAILED, logReason);
                    failure = "JVM observation failed" + (failure == null ? "" : "; experiment: " + failure);
                    log.error("job_id={} stage=jvm-observation reason=capture_or_publish_failed", output.runId(), ex);
                    interrupted |= Thread.interrupted();
                }
                if (recording != null) {
                    ArtifactReference capture = null;
                    try {
                        capture = recording.finish();
                        output.artifacts().accept(capture);
                    }
                    catch (Exception | OutOfMemoryError ex) {
                        status = RunStatus.FAILED;
                        logReason = RunLogRecorder.Reason.JFR_FAILED;
                        if (runLog != null) runLog.record(RunLogRecorder.Stage.JFR, RunLogRecorder.Event.FAILED, logReason);
                        failure = "JFR capture or registration failed" + (failure == null ? "" : "; experiment: " + failure);
                        log.error("job_id={} stage=jfr reason=capture_or_registration_failed artifact_id={}",
                                output.runId(), capture == null ? "unavailable" : capture.artifactId(), ex);
                        interrupted |= Thread.interrupted();
                    } finally { recording.close(); }
                }
                try { output.drainMetrics().run(); }
                catch (RuntimeException ex) {
                    status = RunStatus.FAILED;
                    logReason = RunLogRecorder.Reason.STORAGE_FAILED;
                    if (runLog != null) runLog.record(RunLogRecorder.Stage.STORAGE, RunLogRecorder.Event.FAILED, logReason);
                    failure = "Observation storage failed: " + ex.getMessage()
                            + (failure == null ? "" : "; experiment: " + failure);
                    log.error("job_id={} stage=metric-storage reason=drain_failed", output.runId(), ex);
                    interrupted |= Thread.interrupted();
                }
            } finally {
                if (runLog != null) {
                    ArtifactReference capture = null;
                    try {
                        capture = runLog.finish(logReason);
                        output.artifacts().accept(capture);
                    } catch (Exception | OutOfMemoryError ex) {
                        status = RunStatus.FAILED;
                        failure = "Run log capture or registration failed" + (failure == null ? "" : "; experiment: " + failure);
                        log.error("job_id={} stage=run-log reason=capture_or_registration_failed artifact_id={}",
                                output.runId(), capture == null ? "unavailable" : capture.artifactId(), ex);
                        interrupted |= Thread.interrupted();
                    }
                }
            }
        }
        if (summary == null) summary = ExperimentSummary.empty();
        var counters = new HashMap<>(summary.counters());
        counters.putAll(observation.counters());
        counters.putAll(trace.counters());
        if (runLog != null) counters.putAll(runLog.counters());
        if (recording != null) counters.putAll(recording.counters());
        return new Outcome(status, new ExperimentSummary(counters, summary.gauges(), summary.details()),
                failure, interrupted);
    }
}
