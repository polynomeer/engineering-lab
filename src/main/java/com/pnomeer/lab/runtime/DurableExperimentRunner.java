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
    private final ExperimentExecutors executors;
    private final ExecutionAdmission admission;
    private final Map<String, RunExecution.Definition> definitions;
    private final RunStore store;
    private final Supplier<GitProvenance> provenance;
    private final MetricWriterOptions options;
    private final int previewLimit;
    private final java.util.function.Function<String, Map<String, String>> measurementMetadata;
    private Thread measurementOwner;
    private int measurementDepth;

    /** Thrown by {@link #prepare} while another thread holds the measurement reservation. */
    public static final class MeasurementReserved extends IllegalStateException {
        MeasurementReserved() { super("A repeated-run series is measuring; submit after it finishes or set lab.series.exclusive=false"); }
    }

    /** Re-entrant on the owning thread; the reservation ends when every holder has closed it. */
    public interface MeasurementReservation extends AutoCloseable { @Override void close(); }

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

    public DurableExperimentRunner(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, int previewLimit, int maxActiveRuns, Supplier<MetricPoint> jvmProbe,
            long jvmIntervalMs, int maxPeriodicSamples, boolean traceEnabled, int traceMaxSpans,
            JfrRecorder jfr, RunLogRecorder runLogs, Map<String, String> moduleIds) {
        this(store, provenance, options, ExperimentExecutors.inProcess(new InProcessExecutor(jvmProbe, jvmIntervalMs,
                maxPeriodicSamples, traceEnabled, traceMaxSpans, jfr, runLogs, options.shutdownTimeoutMs()), maxActiveRuns),
                previewLimit, maxActiveRuns, moduleIds.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> new RunExecution.Definition(RunExecution.Kind.IN_PROCESS, entry.getValue(),
                                0, RunExecution.MeasurementScope.CONTROLLER_PROCESS))));
    }

    public static DurableExperimentRunner configured(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, int previewLimit, int maxActiveRuns, Supplier<MetricPoint> jvmProbe,
            long interval, int samples, boolean traceEnabled, int spans, JfrRecorder jfr, RunLogRecorder logs,
            Map<String, String> modules, List<? extends WorkerRegistration> workers) {
        return configured(store, provenance, options, previewLimit, maxActiveRuns, jvmProbe, interval, samples,
                traceEnabled, spans, jfr, logs, modules, workers, null);
    }
    public static DurableExperimentRunner configured(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, int previewLimit, int maxActiveRuns, Supplier<MetricPoint> jvmProbe,
            long interval, int samples, boolean traceEnabled, int spans, JfrRecorder jfr, RunLogRecorder logs,
            Map<String, String> modules, List<? extends WorkerRegistration> workers, WorkerStorageBudget budget) {
        return configured(store, provenance, options, previewLimit, maxActiveRuns, jvmProbe, interval, samples,
                traceEnabled, spans, jfr, logs, modules, workers, budget, id -> Map.of());
    }
    public static DurableExperimentRunner configured(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, int previewLimit, int maxActiveRuns, Supplier<MetricPoint> jvmProbe,
            long interval, int samples, boolean traceEnabled, int spans, JfrRecorder jfr, RunLogRecorder logs,
            Map<String, String> modules, List<? extends WorkerRegistration> workers, WorkerStorageBudget budget,
            java.util.function.Function<String, Map<String, String>> measurementMetadata) {
        var registrations = new HashMap<String, ExperimentExecutors.Registration>();
        var bindings = new HashMap<String, ExperimentExecutors.Binding>();
        var definitions = new HashMap<String, RunExecution.Definition>();
        registrations.put("IN_PROCESS", new ExperimentExecutors.Registration(new InProcessExecutor(jvmProbe,
                interval, samples, traceEnabled, spans, jfr, logs, options.shutdownTimeoutMs()), maxActiveRuns));
        modules.forEach((id, module) -> definitions.put(id, new RunExecution.Definition(RunExecution.Kind.IN_PROCESS,
                module, 0, RunExecution.MeasurementScope.CONTROLLER_PROCESS)));
        for (var worker : workers) {
            if (registrations.putIfAbsent(worker.id(), new ExperimentExecutors.Registration(budget == null ? worker.executor() : budget.wrap(worker.executor()), 1)) != null)
                throw new IllegalArgumentException("Duplicate worker registration");
            for (String id : worker.experiments()) {
                if (!worker.moduleId().equals(modules.get(id)) || bindings.putIfAbsent(id,
                        new ExperimentExecutors.Binding(worker.id(), true)) != null)
                    throw new IllegalArgumentException("Worker catalog ownership mismatch");
                definitions.put(id, new RunExecution.Definition(worker.kind(),
                        worker.moduleId(), 1, RunExecution.MeasurementScope.WORKER_PROCESS));
            }
        }
        return new DurableExperimentRunner(store, provenance, options, new ExperimentExecutors("IN_PROCESS",
                registrations, bindings), previewLimit, maxActiveRuns, definitions, measurementMetadata);
    }

    DurableExperimentRunner(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, ExperimentExecutor executor, int previewLimit, int maxActiveRuns) {
        this(store, provenance, options, ExperimentExecutors.inProcess(executor, maxActiveRuns), previewLimit, maxActiveRuns);
    }

    DurableExperimentRunner(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, ExperimentExecutors executors, int previewLimit, int maxActiveRuns) {
        this(store, provenance, options, executors, previewLimit, maxActiveRuns, Map.of());
    }

    DurableExperimentRunner(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, ExperimentExecutors executors, int previewLimit, int maxActiveRuns,
            Map<String, RunExecution.Definition> definitions) {
        this(store, provenance, options, executors, previewLimit, maxActiveRuns, definitions, id -> Map.of());
    }
    DurableExperimentRunner(RunStore store, Supplier<GitProvenance> provenance,
            MetricWriterOptions options, ExperimentExecutors executors, int previewLimit, int maxActiveRuns,
            Map<String, RunExecution.Definition> definitions,
            java.util.function.Function<String, Map<String, String>> measurementMetadata) {
        this.measurementMetadata = Objects.requireNonNull(measurementMetadata);
        this.definitions = Map.copyOf(definitions);
        this.executors = Objects.requireNonNull(executors);
        this.admission = new ExecutionAdmission(maxActiveRuns);
        if (previewLimit < 1 || maxActiveRuns < 1) throw new IllegalArgumentException("Run limits must be positive");
        this.store = store; this.provenance = provenance; this.options = options;
        this.previewLimit = previewLimit;
    }

    /**
     * Reserves the runner for the calling thread, so runs prepared elsewhere cannot share this JVM with a
     * repeated measurement. Runs already admitted keep running; callers detect them by their timestamps.
     */
    public synchronized MeasurementReservation reserveMeasurement() {
        Thread current = Thread.currentThread();
        if (measurementOwner != null && measurementOwner != current) throw new MeasurementReserved();
        measurementOwner = current;
        measurementDepth++;
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            synchronized (this) {
                if (closed.compareAndSet(false, true) && measurementOwner == current && --measurementDepth == 0) measurementOwner = null;
            }
        };
    }

    private synchronized void requireMeasurementOwner() {
        if (measurementOwner != null && measurementOwner != Thread.currentThread()) throw new MeasurementReserved();
    }

    public <C extends ExperimentScenario> StoredRun prepare(Experiment<C> experiment, C scenario, String id) {
        admission.checkAccepting();
        requireMeasurementOwner();
        var executor = executors.select(experiment.type()).executor();
        executor.preflight();
        var parameters = new HashMap<>(experiment.parameters(scenario));
        parameters.put("evidence.dead-letter.mode", "DATABASE_V1");
        parameters.put("evidence.dead-letter.maxRecordBytes", String.valueOf(DeadLetter.MAX_BYTES));
        parameters.putAll(executor.parameters());
        parameters.putAll(measurementMetadata.apply(experiment.type()));
        var metadata = new RunMetadata(id, parameters, RunEnvironment.capture(), provenance.get());
        var definition = definitions.get(experiment.type());
        return admission.submit(() -> definition == null ? store.create(experiment.type(), scenario.scenarioId(), metadata)
                : store.create(experiment.type(), scenario.scenarioId(), metadata, definition));
    }

    @Override
    public <C extends ExperimentScenario> ExperimentResult run(Experiment<C> experiment, C scenario, String id) {
        return runPrepared(experiment, scenario, prepare(experiment, scenario, id));
    }

    public void reject(StoredRun run, String reason) {
        store.complete(run.runId(), RunStatus.FAILED, ExperimentSummary.empty(), reason);
    }

    public <C extends ExperimentScenario> ExperimentResult runPrepared(Experiment<C> experiment, C scenario, StoredRun run) {
        if (!run.experimentType().equals(experiment.type()) || !run.scenarioId().equals(scenario.scenarioId())) {
            throw new IllegalArgumentException("Prepared Run does not match the execution request");
        }
        var selection = executors.select(run.experimentType());
        boolean executionRecorded = definitions.containsKey(run.experimentType());
        var lease = admission.acquire(run.runId(), selection, reason -> reject(run, reason));
        try (lease) {
            store.start(run.runId());
            var hostLoad = new HashMap<>(com.pnomeer.lab.metrics.HostLoad.sample("start"));
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
            }), store::registerArtifact, writer::close,
                    phase -> { if (executionRecorded) store.advanceExecution(run.runId(), phase); },
                    worker -> store.registerWorker(run.runId(), worker),
                    check -> store.appendCheck(run.runId(), check));
            var outcome = selection.executor().execute(experiment, scenario, output);
            var status = outcome.status();
            var summary = outcome.summary();
            var failure = outcome.failure();
            boolean interrupted = outcome.interrupted();
            var counters = new HashMap<>(summary.counters());
            counters.putAll(writer.counters());
            counters.put("storage.preview.limit", (long) previewLimit);
            hostLoad.putAll(com.pnomeer.lab.metrics.HostLoad.sample("end"));
            var gauges = new HashMap<>(hostLoad);
            gauges.putAll(summary.gauges());
            summary = new ExperimentSummary(counters, gauges, summary.details());
            try {
                if (executionRecorded && status == RunStatus.SUCCEEDED) {
                    try { store.finishExecutionEvidence(run.runId()); }
                    catch (RuntimeException | OutOfMemoryError ex) {
                        status = RunStatus.FAILED;
                        failure = "Execution evidence finalization failed";
                        interrupted |= Thread.interrupted();
                        log.error("job_id={} stage=execution-evidence reason=finalization_failed", run.runId(), ex);
                    }
                }
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
        }
    }

    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void stopAccepting() { admission.stopAccepting(); }

    @Override
    public void close() { admission.close(options.shutdownTimeoutMs()); }
}
