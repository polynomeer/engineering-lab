package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.BoundedMetricWriter;
import com.pnomeer.lab.storage.MetricWriterOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

public final class DurableExperimentRunner implements ExperimentRunner, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DurableExperimentRunner.class);
    private final ExperimentExecutor executor;
    private final RunStore store;
    private final Supplier<GitProvenance> provenance;
    private final MetricWriterOptions options;
    private final int previewLimit, maxActiveRuns;
    private final Set<Thread> active = new HashSet<>();
    private boolean accepting = true;
    private boolean closing;

    public DurableExperimentRunner(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, int previewLimit, int maxActiveRuns) {
        this(store, provenance, options, previewLimit, maxActiveRuns, null);
    }

    public DurableExperimentRunner(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, int previewLimit, int maxActiveRuns, Supplier<MetricPoint> jvmProbe) {
        this(store, provenance, options, previewLimit, maxActiveRuns, jvmProbe, 0, 1000);
    }

    public DurableExperimentRunner(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, int previewLimit, int maxActiveRuns, Supplier<MetricPoint> jvmProbe,
            long jvmIntervalMs, int maxPeriodicSamples) {
        this(store, provenance, options, previewLimit, maxActiveRuns, jvmProbe, jvmIntervalMs, maxPeriodicSamples, false, 1000);
    }

    public DurableExperimentRunner(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, int previewLimit, int maxActiveRuns, Supplier<MetricPoint> jvmProbe,
            long jvmIntervalMs, int maxPeriodicSamples, boolean traceEnabled, int traceMaxSpans) {
        this(store, provenance, options, previewLimit, maxActiveRuns, jvmProbe, jvmIntervalMs,
                maxPeriodicSamples, traceEnabled, traceMaxSpans, null);
    }

    public DurableExperimentRunner(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, int previewLimit, int maxActiveRuns, Supplier<MetricPoint> jvmProbe,
            long jvmIntervalMs, int maxPeriodicSamples, boolean traceEnabled, int traceMaxSpans, JfrRecorder jfr) {
        this(store, provenance, options, previewLimit, maxActiveRuns, jvmProbe, jvmIntervalMs,
                maxPeriodicSamples, traceEnabled, traceMaxSpans, jfr, null);
    }

    public DurableExperimentRunner(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, int previewLimit, int maxActiveRuns, Supplier<MetricPoint> jvmProbe,
            long jvmIntervalMs, int maxPeriodicSamples, boolean traceEnabled, int traceMaxSpans,
            JfrRecorder jfr, RunLogRecorder runLogs) {
        this(store, provenance, options, new InProcessExecutor(jvmProbe, jvmIntervalMs,
                maxPeriodicSamples, traceEnabled, traceMaxSpans, jfr, runLogs, options.shutdownTimeoutMs()),
                previewLimit, maxActiveRuns);
    }

    DurableExperimentRunner(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, ExperimentExecutor executor, int previewLimit, int maxActiveRuns) {
        this.executor = Objects.requireNonNull(executor);
        if (previewLimit < 1 || maxActiveRuns < 1) throw new IllegalArgumentException("Run limits must be positive");
        this.store = store; this.provenance = provenance; this.options = options;
        this.previewLimit = previewLimit; this.maxActiveRuns = maxActiveRuns;
    }

    public <C extends ExperimentScenario> StoredRun prepare(Experiment<C> experiment, C scenario, String id) {
        synchronized (active) {
            if (!accepting) throw new IllegalStateException("Experiment runner is shutting down");
        }
        var parameters = new HashMap<>(experiment.parameters(scenario));
        parameters.put("evidence.dead-letter.mode", "DATABASE_V1");
        parameters.put("evidence.dead-letter.maxRecordBytes", String.valueOf(DeadLetter.MAX_BYTES));
        parameters.putAll(executor.parameters());
        var metadata = new RunMetadata(id, parameters, executor.environment(), provenance.get());
        synchronized (active) {
            if (!accepting) throw new IllegalStateException("Experiment runner is shutting down");
            return store.create(experiment.type(), scenario.scenarioId(), metadata);
        }
    }

    @Override
    public <C extends ExperimentScenario> ExperimentResult run(Experiment<C> experiment, C scenario, String id) {
        return runPrepared(experiment, scenario, prepare(experiment, scenario, id));
    }

    public void reject(StoredRun run, String reason) {
        store.complete(run.runId(), RunStatus.FAILED, ExperimentSummary.empty(), reason);
    }

    public <C extends ExperimentScenario> ExperimentResult runPrepared(Experiment<C> experiment, C scenario, StoredRun run) {
        synchronized (active) {
            if (closing || active.size() >= maxActiveRuns) {
                reject(run, "Execution admission limit or shutdown");
                throw new IllegalStateException("Execution admission limit or shutdown");
            }
            active.add(Thread.currentThread());
        }
        try {
            store.start(run.runId());
            long started = System.currentTimeMillis();
            var preview = new ArrayDeque<MetricPoint>(previewLimit);
            var writer = new BoundedMetricWriter(store, run.runId(), options);
            var output = new ExperimentExecutor.Output(run.runId(), writer, point -> {
                writer.accept(point);
                synchronized (preview) {
                    if (preview.size() == previewLimit) preview.removeFirst();
                    preview.addLast(point);
                }
            }, new ExecutionEvidence(run.runId(), row -> {
                try { store.appendDeadLetter(run.runId(), row); }
                catch (RuntimeException ex) {
                    log.error("job_id={} stage=dead-letter reason=persistence_failed row_index={}", run.runId(), row.rowIndex());
                    throw new IllegalStateException("Dead-letter persistence failed", ex);
                }
            }), store::registerArtifact, writer::close);
            var outcome = executor.execute(experiment, scenario, output);
            var status = outcome.status();
            var summary = outcome.summary();
            var failure = outcome.failure();
            boolean interrupted = outcome.interrupted();
            var counters = new HashMap<>(summary.counters());
            counters.putAll(writer.counters());
            counters.put("storage.preview.limit", (long) previewLimit);
            summary = new ExperimentSummary(counters, summary.gauges(), summary.details());
            try {
                store.complete(run.runId(), status, summary, failure);
                log.info("job_id={} stage=run-storage status={} written={} retries={}", run.runId(), status,
                        counters.get("storage.metrics.written"), counters.get("storage.retries"));
                synchronized (preview) {
                    return new ExperimentResult(run.experimentType(), run.scenarioId(),
                            status == RunStatus.SUCCEEDED ? ExperimentStatus.SUCCEEDED : ExperimentStatus.FAILED,
                            started, System.currentTimeMillis(), summary, failure, List.copyOf(preview), run.runMetadata());
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        } catch (RuntimeException | Error ex) {
            log.error("job_id={} stage=run-storage reason=execution_or_persistence_failed", run.runId(), ex);
            throw ex;
        } finally {
            synchronized (active) { active.remove(Thread.currentThread()); active.notifyAll(); }
        }
    }

    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void stopAccepting() {
        synchronized (active) { accepting = false; }
    }

    @Override
    public void close() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(options.shutdownTimeoutMs());
        synchronized (active) {
            accepting = false;
            closing = true;
            while (!active.isEmpty()) {
                long left = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) break;
                try { active.wait(left); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
            }
            if (!active.isEmpty()) {
                log.error("stage=run-storage reason=shutdown_timeout active_runs={}", active.size());
                active.forEach(Thread::interrupt);
            }
        }
    }
}
