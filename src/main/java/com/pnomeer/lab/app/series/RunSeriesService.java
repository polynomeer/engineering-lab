package com.pnomeer.lab.app.series;

import com.pnomeer.lab.app.run.ScenarioRunService;
import com.pnomeer.lab.core.input.InputPayload;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.registry.measurement.MeasurementContract;
import com.pnomeer.lab.runtime.DurableExperimentRunner;
import com.pnomeer.lab.scenario.ScenarioDefinition;
import com.pnomeer.lab.scenario.ScenarioDocuments;
import com.pnomeer.lab.scenario.ScenarioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Runs warmup then measured repetitions of one definition, strictly one at a time. */
@Service
public class RunSeriesService {
    private static final Logger log = LoggerFactory.getLogger(RunSeriesService.class);
    private static final Set<RunStatus> TERMINAL = EnumSet.of(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.INTERRUPTED);
    private final ScenarioRunService runs;
    private final ScenarioDocuments documents;
    private final RunStore runStore;
    private final RunSeriesStore seriesStore;
    private final TaskExecutor executor;
    private final SeriesProperties properties;
    private final ScenarioProperties scenarioProperties;
    private final DurableExperimentRunner runner;

    public RunSeriesService(ScenarioRunService runs, ScenarioDocuments documents,
            com.pnomeer.lab.storage.JdbcRunStore store, @Qualifier("seriesTaskExecutor") TaskExecutor executor,
            SeriesProperties properties, ScenarioProperties scenarioProperties, DurableExperimentRunner runner) {
        this.runs = runs; this.documents = documents; this.runStore = store; this.seriesStore = store;
        this.executor = executor; this.properties = properties; this.scenarioProperties = scenarioProperties;
        this.runner = runner;
    }

    /** Takes ownership of {@code input}; it is closed when the series ends or is rejected. */
    public RunSeries submit(ScenarioDefinition definition, InputPayload input, int repetitions, int warmup) {
        RunSeries series;
        try {
            series = create(definition, repetitions, warmup);
        } catch (RuntimeException ex) {
            closeQuietly(input, ex);
            throw ex;
        }
        try { executor.execute(() -> coordinate(series, definition, input)); }
        catch (TaskRejectedException ex) {
            closeQuietly(input, ex);
            seriesStore.completeSeries(series.seriesId(), RunStatus.FAILED, "Series queue is full or shutting down");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Series queue is full or shutting down", ex);
        }
        return series;
    }

    RunSeries create(ScenarioDefinition definition, int repetitions, int warmup) {
        return seriesStore.createSeries(UUID.randomUUID().toString(), definition.experiment(), definition.scenario().id(),
                documents.hash(definition), repetitions, warmup);
    }

    /** Runs the series on the calling thread and returns its final status. Closes {@code input}. */
    RunStatus coordinate(RunSeries series, ScenarioDefinition definition, InputPayload input) {
        String id = series.seriesId();
        try (input; var reservation = reserve()) {
            seriesStore.startSeries(id);
            awaitIdle(id);
            int failedMeasured = 0;
            for (int position = 0; position < series.total(); position++) {
                boolean warmup = position < series.warmup();
                String runId = runs.submit(definition, copy(input));
                seriesStore.addSeriesRun(id, new RunSeries.Member(position, runId, warmup));
                RunStatus status = await(runId);
                if (status != RunStatus.SUCCEEDED && !warmup) failedMeasured++;
                log.info("series_id={} stage=series position={} run_id={} warmup={} status={}", id, position, runId, warmup, status);
            }
            var status = failedMeasured == 0 ? RunStatus.SUCCEEDED : RunStatus.FAILED;
            seriesStore.completeSeries(id, status,
                    failedMeasured == 0 ? null : failedMeasured + " of " + series.repetitions() + " measured repetitions did not succeed");
            return status;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finish(id, RunStatus.INTERRUPTED, "Series interrupted before all repetitions finished");
            return RunStatus.INTERRUPTED;
        } catch (RuntimeException | java.io.IOException ex) {
            log.warn("series_id={} stage=series reason={}", id, ex.getMessage());
            finish(id, RunStatus.FAILED, "Series stopped: " + ex.getMessage());
            return RunStatus.FAILED;
        }
    }

    /** Sweeps hold one reservation across all points; the runner's reservation is re-entrant. */
    DurableExperimentRunner.MeasurementReservation reserve() {
        return properties.exclusive() ? runner.reserveMeasurement() : () -> { };
    }

    /** Runs admitted before the reservation would overlap the first repetition; give them time to finish. */
    private void awaitIdle(String seriesId) throws InterruptedException {
        long deadline = System.nanoTime() + properties.runTimeoutMs() * 1_000_000;
        List<String> busy;
        while (!(busy = seriesStore.unfinishedRunIds(20)).isEmpty()) {
            if (System.nanoTime() > deadline) {
                log.warn("series_id={} stage=series reason=other runs still active, measuring anyway: {}", seriesId, busy);
                return;
            }
            Thread.sleep(properties.pollIntervalMs());
        }
    }

    InputPayload copy(InputPayload input) throws java.io.IOException {
        // Each run owns and deletes its own staged copy; the series keeps the original until it ends.
        return input == null ? null : InputPayload.stage(input.openStream(), scenarioProperties.maxInputBytes());
    }

    private RunStatus await(String runId) throws InterruptedException {
        long deadline = System.nanoTime() + properties.runTimeoutMs() * 1_000_000;
        while (true) {
            var run = runStore.find(runId).orElseThrow(() -> new IllegalStateException("Run disappeared: " + runId));
            if (TERMINAL.contains(run.status())) return run.status();
            if (System.nanoTime() > deadline) throw new IllegalStateException("Run " + runId + " exceeded lab.series.run-timeout-ms");
            Thread.sleep(properties.pollIntervalMs());
        }
    }

    private void finish(String id, RunStatus status, String message) {
        try { seriesStore.completeSeries(id, status, message); }
        catch (RuntimeException ex) { log.warn("series_id={} stage=series reason=cannot record completion: {}", id, ex.getMessage()); }
    }

    private static void closeQuietly(InputPayload input, Throwable failure) {
        if (input == null) return;
        try { input.close(); } catch (RuntimeException close) { failure.addSuppressed(close); }
    }

    public Optional<SeriesView> view(String seriesId) {
        return seriesStore.findSeries(seriesId).map(this::view);
    }

    public List<RunSeries> list(String experimentType, int limit) { return seriesStore.listSeries(experimentType, limit); }

    SeriesView view(RunSeries series) {
        var members = seriesStore.seriesRuns(series.seriesId());
        var runsById = new LinkedHashMap<String, StoredRun>();
        for (var member : members) runStore.find(member.runId()).ifPresent(run -> runsById.put(run.runId(), run));
        var rows = members.stream().map(m -> {
            var run = runsById.get(m.runId());
            return new SeriesView.Member(m.position(), m.runId(), m.warmup(), run == null ? null : run.status(),
                    run == null ? 0 : run.startedAtEpochMs(), run == null ? 0 : run.completedAtEpochMs());
        }).toList();
        var measured = members.stream().filter(m -> !m.warmup()).map(m -> runsById.get(m.runId()))
                .filter(r -> r != null && r.status() == RunStatus.SUCCEEDED).toList();
        // Use the contract captured on the runs themselves, never today's catalog.
        var contract = measured.stream().map(r -> MeasurementContract.read(r.runMetadata().parameters().get(MeasurementContract.PARAMETER), r.experimentType()))
                .flatMap(Optional::stream).findFirst();
        var measures = contract.map(c -> c.measures().stream().map(measure -> new SeriesView.Measure(measure.role(), measure.name(),
                measure.unit(), measure.kind().name(), SeriesStatistics.of(measured.stream().map(r -> value(r, measure)).toList()))).toList())
                .orElse(List.of());
        return new SeriesView(series, rows, measured.size(), measures, overlapping(rows), stability(measured, measures));
    }

    static SeriesView.Stability stability(List<StoredRun> measured, List<SeriesView.Measure> measures) {
        var unstable = measures.stream().filter(m -> m.statistics().count() >= SeriesStatistics.MIN_FOR_COMPARISON
                && m.statistics().coefficientOfVariation() != null
                && m.statistics().coefficientOfVariation() > SeriesView.Stability.HIGH_VARIATION).map(SeriesView.Measure::role).toList();
        var loads = measured.stream().flatMap(r -> java.util.stream.Stream.of(r.summary().gauges().get("host.load1m.start"),
                r.summary().gauges().get("host.load1m.end"))).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sorted().toArray();
        Integer processors = measured.isEmpty() ? null : measured.getFirst().runMetadata().environment().availableProcessors();
        return new SeriesView.Stability(SeriesView.Stability.HIGH_VARIATION, unstable,
                loads.length == 0 ? null : loads[0], loads.length == 0 ? null : loads[loads.length - 1], processors);
    }

    private List<String> overlapping(List<SeriesView.Member> rows) {
        var started = rows.stream().filter(m -> m.startedAtEpochMs() > 0).toList();
        if (started.isEmpty()) return List.of();
        long from = started.stream().mapToLong(SeriesView.Member::startedAtEpochMs).min().orElseThrow();
        long to = started.stream().anyMatch(m -> m.completedAtEpochMs() == 0) ? Long.MAX_VALUE
                : started.stream().mapToLong(SeriesView.Member::completedAtEpochMs).max().orElseThrow();
        var own = rows.stream().map(SeriesView.Member::runId).collect(java.util.stream.Collectors.toSet());
        // A run inside the window but between two repetitions is listed too: it is rare and worth a look.
        return seriesStore.runsActiveBetween(from, to, 20 + own.size()).stream().filter(r -> !own.contains(r)).limit(20).toList();
    }

    public SeriesComparison compare(String baselineId, String candidateId) {
        var baseline = view(baselineId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown baseline series"));
        var candidate = view(candidateId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown candidate series"));
        if (!baseline.series().experimentType().equals(candidate.series().experimentType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Series must belong to the same experiment");
        return SeriesComparison.of(baseline, candidate);
    }

    /** Pins a finished series with enough measured repetitions as its scenario's baseline. */
    public RunSeries pinBaseline(String seriesId) {
        var view = view(seriesId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown series"));
        if (view.series().status() != RunStatus.SUCCEEDED || view.measuredSucceeded() < SeriesStatistics.MIN_FOR_COMPARISON)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A baseline needs a succeeded series with at least " + SeriesStatistics.MIN_FOR_COMPARISON + " measured repetitions");
        seriesStore.pinBaseline(seriesId);
        return view.series();
    }

    public List<RunSeries> baselines(int limit) { return seriesStore.baselines(limit); }

    public RegressionCheck regression(String seriesId, double tolerancePct) {
        var candidate = seriesStore.findSeries(seriesId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown series"));
        if (!(tolerancePct >= 0 && tolerancePct <= 100)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tolerancePct must be 0..100");
        var baseline = seriesStore.baseline(candidate.experimentType(), candidate.scenarioId())
                .filter(b -> !b.seriesId().equals(seriesId));
        if (baseline.isEmpty()) return RegressionCheck.noBaseline(seriesId, tolerancePct);
        return RegressionCheck.of(compare(baseline.get().seriesId(), seriesId), tolerancePct);
    }

    /** Raw measured values of a baseline-worthy series, for committing or carrying to another store. */
    public BaselineFile baselineFile(String seriesId) {
        var view = view(seriesId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown series"));
        if (view.series().status() != RunStatus.SUCCEEDED || view.measuredSucceeded() < SeriesStatistics.MIN_FOR_COMPARISON)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A baseline needs a succeeded series with at least " + SeriesStatistics.MIN_FOR_COMPARISON + " measured repetitions");
        var measured = measuredRuns(view);
        var contract = measured.stream().map(r -> MeasurementContract.read(r.runMetadata().parameters().get(MeasurementContract.PARAMETER), r.experimentType()))
                .flatMap(Optional::stream).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Series runs carry no measurement contract"));
        var measures = contract.measures().stream().map(m -> new BaselineFile.Measure(m.role(), m.name(), m.unit(), m.kind().name(),
                measured.stream().map(r -> value(r, m)).filter(Objects::nonNull).toList())).toList();
        var first = measured.getFirst();
        return new BaselineFile(BaselineFile.SCHEMA_VERSION, view.series().experimentType(), view.series().scenarioId(),
                view.series().definitionSha256(), seriesId, System.currentTimeMillis(), first.runMetadata().git().commit(),
                first.runMetadata().environment(), measures);
    }

    /** Checks a finished series against a baseline file instead of the store's pinned baseline. */
    public RegressionCheck regression(String seriesId, BaselineFile baseline, double tolerancePct) {
        if (!(tolerancePct >= 0 && tolerancePct <= 100)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tolerancePct must be 0..100");
        var candidate = view(seriesId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown series"));
        if (!baseline.experimentType().equals(candidate.series().experimentType()) || !baseline.scenarioId().equals(candidate.series().scenarioId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Baseline file is for " + baseline.experimentType() + "/" + baseline.scenarioId());
        var cautions = new ArrayList<String>();
        if (!baseline.definitionSha256().equals(candidate.series().definitionSha256()))
            cautions.add("Scenario definition differs from the baseline file");
        var measured = measuredRuns(candidate);
        if (!measured.isEmpty())
            baseline.environmentDifferences(measured.getFirst().runMetadata().environment()).forEach(d -> cautions.add("Environment differs: " + d));
        return RegressionCheck.of(SeriesComparison.of(baseline.view(), candidate), tolerancePct).withCautions(cautions);
    }

    private List<StoredRun> measuredRuns(SeriesView view) {
        return view.members().stream().filter(m -> !m.warmup() && m.status() == RunStatus.SUCCEEDED)
                .map(m -> runStore.find(m.runId())).flatMap(Optional::stream).toList();
    }

    private static Double value(StoredRun run, MeasurementContract.Measure measure) {
        Number value = measure.kind() == MeasurementContract.Kind.counter
                ? run.summary().counters().get(measure.name()) : run.summary().gauges().get(measure.name());
        return value == null ? null : value.doubleValue();
    }
}
