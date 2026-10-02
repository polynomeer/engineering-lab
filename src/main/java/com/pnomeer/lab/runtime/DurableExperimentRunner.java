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
    private final RunStore store;
    private final Supplier<GitProvenance> provenance;
    private final MetricWriterOptions options;
    private final int previewLimit, maxActiveRuns;
    private final Set<Thread> active = new HashSet<>();
    private boolean accepting = true;
    private boolean closing;

    public DurableExperimentRunner(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, int previewLimit, int maxActiveRuns) {
        if (previewLimit < 1 || maxActiveRuns < 1) throw new IllegalArgumentException("Run limits must be positive");
        this.store = store; this.provenance = provenance; this.options = options;
        this.previewLimit = previewLimit; this.maxActiveRuns = maxActiveRuns;
    }

    public <C extends ExperimentScenario> StoredRun prepare(Experiment<C> experiment, C scenario, String id) {
        synchronized (active) {
            if (!accepting) throw new IllegalStateException("Experiment runner is shutting down");
        }
        var metadata = new RunMetadata(id, experiment.parameters(scenario), RunEnvironment.capture(), provenance.get());
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
            ExperimentSummary summary = ExperimentSummary.empty();
            RunStatus status = RunStatus.SUCCEEDED;
            String failure = null;
            boolean interrupted = false;
            try {
                summary = experiment.execute(scenario, point -> {
                    writer.accept(point);
                    synchronized (preview) {
                        if (preview.size() == previewLimit) preview.removeFirst();
                        preview.addLast(point);
                    }
                });
            } catch (Exception | OutOfMemoryError ex) {
                status = ex instanceof InterruptedException ? RunStatus.INTERRUPTED : RunStatus.FAILED;
                failure = ex instanceof OutOfMemoryError ? "Out of memory"
                        : ex.getMessage() == null ? "Experiment failed" : ex.getMessage();
                interrupted = Thread.interrupted() || ex instanceof InterruptedException;
            } finally {
                try { writer.close(); }
                catch (RuntimeException ex) {
                    status = RunStatus.FAILED;
                    failure = "Observation storage failed: " + ex.getMessage()
                            + (failure == null ? "" : "; experiment: " + failure);
                    log.error("job_id={} stage=metric-storage reason=drain_failed", run.runId(), ex);
                    interrupted |= Thread.interrupted();
                }
            }
            if (summary == null) summary = ExperimentSummary.empty();
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
