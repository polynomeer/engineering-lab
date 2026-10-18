package com.pnomeer.lab.app.series;

import com.pnomeer.lab.app.run.ScenarioRunService;
import com.pnomeer.lab.core.input.InputPayload;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.registry.measurement.MeasurementContract;
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

    public RunSeriesService(ScenarioRunService runs, ScenarioDocuments documents,
            com.pnomeer.lab.storage.JdbcRunStore store, @Qualifier("seriesTaskExecutor") TaskExecutor executor,
            SeriesProperties properties, ScenarioProperties scenarioProperties) {
        this.runs = runs; this.documents = documents; this.runStore = store; this.seriesStore = store;
        this.executor = executor; this.properties = properties; this.scenarioProperties = scenarioProperties;
    }

    /** Takes ownership of {@code input}; it is closed when the series ends or is rejected. */
    public RunSeries submit(ScenarioDefinition definition, InputPayload input, int repetitions, int warmup) {
        RunSeries series;
        try {
            series = seriesStore.createSeries(UUID.randomUUID().toString(), definition.experiment(), definition.scenario().id(),
                    documents.hash(definition), repetitions, warmup);
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

    void coordinate(RunSeries series, ScenarioDefinition definition, InputPayload input) {
        String id = series.seriesId();
        try (input) {
            seriesStore.startSeries(id);
            int failedMeasured = 0;
            for (int position = 0; position < series.total(); position++) {
                boolean warmup = position < series.warmup();
                String runId = runs.submit(definition, copy(input));
                seriesStore.addSeriesRun(id, new RunSeries.Member(position, runId, warmup));
                RunStatus status = await(runId);
                if (status != RunStatus.SUCCEEDED && !warmup) failedMeasured++;
                log.info("series_id={} stage=series position={} run_id={} warmup={} status={}", id, position, runId, warmup, status);
            }
            seriesStore.completeSeries(id, failedMeasured == 0 ? RunStatus.SUCCEEDED : RunStatus.FAILED,
                    failedMeasured == 0 ? null : failedMeasured + " of " + series.repetitions() + " measured repetitions did not succeed");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finish(id, RunStatus.INTERRUPTED, "Series interrupted before all repetitions finished");
        } catch (RuntimeException | java.io.IOException ex) {
            log.warn("series_id={} stage=series reason={}", id, ex.getMessage());
            finish(id, RunStatus.FAILED, "Series stopped: " + ex.getMessage());
        }
    }

    private InputPayload copy(InputPayload input) throws java.io.IOException {
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
        return new SeriesView(series, rows, measured.size(), measures);
    }

    public SeriesComparison compare(String baselineId, String candidateId) {
        var baseline = view(baselineId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown baseline series"));
        var candidate = view(candidateId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown candidate series"));
        if (!baseline.series().experimentType().equals(candidate.series().experimentType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Series must belong to the same experiment");
        return SeriesComparison.of(baseline, candidate);
    }

    private static Double value(StoredRun run, MeasurementContract.Measure measure) {
        Number value = measure.kind() == MeasurementContract.Kind.counter
                ? run.summary().counters().get(measure.name()) : run.summary().gauges().get(measure.name());
        return value == null ? null : value.doubleValue();
    }
}
