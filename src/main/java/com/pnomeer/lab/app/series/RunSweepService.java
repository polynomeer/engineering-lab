package com.pnomeer.lab.app.series;

import com.pnomeer.lab.core.input.InputPayload;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.registry.ExperimentRegistry;
import com.pnomeer.lab.scenario.ScenarioDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Runs one series per grid point on the series coordinator thread, strictly one after another. */
@Service
public class RunSweepService {
    private static final Logger log = LoggerFactory.getLogger(RunSweepService.class);
    private final RunSeriesService series;
    private final RunSeriesStore store;
    private final ExperimentRegistry registry;
    private final TaskExecutor executor;

    public RunSweepService(RunSeriesService series, com.pnomeer.lab.storage.JdbcRunStore store, ExperimentRegistry registry,
            @Qualifier("seriesTaskExecutor") TaskExecutor executor) {
        this.series = series; this.store = store; this.registry = registry; this.executor = executor;
    }

    /** Validates every point before anything runs. Takes ownership of {@code input}. */
    public RunSweep submit(ScenarioDefinition base, Map<String, List<Object>> grid, InputPayload input, int repetitions, int warmup) {
        List<SweepGrid.Point> points;
        RunSweep sweep;
        try {
            points = SweepGrid.expand(base, grid, repetitions, warmup);
            for (var point : points) registry.validate(point.definition());
            sweep = store.createSweep(UUID.randomUUID().toString(), base.experiment(), base.scenario().id(), grid, repetitions, warmup);
        } catch (RuntimeException ex) {
            if (input != null) try { input.close(); } catch (RuntimeException close) { ex.addSuppressed(close); }
            throw ex;
        }
        try { executor.execute(() -> coordinate(sweep, points, input, repetitions, warmup)); }
        catch (TaskRejectedException ex) {
            if (input != null) input.close();
            store.completeSweep(sweep.sweepId(), RunStatus.FAILED, "Series queue is full or shutting down");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Series queue is full or shutting down", ex);
        }
        return sweep;
    }

    void coordinate(RunSweep sweep, List<SweepGrid.Point> points, InputPayload input, int repetitions, int warmup) {
        String id = sweep.sweepId();
        try (input; var reservation = series.reserve()) {
            store.startSweep(id);
            int failed = 0;
            for (var point : points) {
                if (Thread.currentThread().isInterrupted()) {
                    finish(id, RunStatus.INTERRUPTED, "Sweep interrupted before all points finished");
                    return;
                }
                var pointSeries = series.create(point.definition(), repetitions, warmup);
                store.addSweepPoint(id, new RunSweep.Point(point.position(), pointSeries.seriesId(), point.overrides()));
                var status = series.coordinate(pointSeries, point.definition(), series.copy(input));
                log.info("sweep_id={} stage=sweep position={} series_id={} status={}", id, point.position(), pointSeries.seriesId(), status);
                if (status == RunStatus.INTERRUPTED) {
                    finish(id, RunStatus.INTERRUPTED, "Sweep interrupted before all points finished");
                    return;
                }
                if (status != RunStatus.SUCCEEDED) failed++;
            }
            finish(id, failed == 0 ? RunStatus.SUCCEEDED : RunStatus.FAILED,
                    failed == 0 ? null : failed + " of " + points.size() + " points did not succeed");
        } catch (RuntimeException | java.io.IOException ex) {
            log.warn("sweep_id={} stage=sweep reason={}", id, ex.getMessage());
            finish(id, RunStatus.FAILED, "Sweep stopped: " + ex.getMessage());
        }
    }

    private void finish(String id, RunStatus status, String message) {
        try { store.completeSweep(id, status, message); }
        catch (RuntimeException ex) { log.warn("sweep_id={} stage=sweep reason=cannot record completion: {}", id, ex.getMessage()); }
    }

    public List<RunSweep> list(String experimentType, int limit) { return store.listSweeps(experimentType, limit); }

    public Optional<SweepView> view(String sweepId) {
        return store.findSweep(sweepId).map(sweep -> new SweepView(sweep, store.sweepPoints(sweepId).stream()
                .map(point -> new SweepView.Point(point.position(), point.parameters(),
                        store.findSeries(point.seriesId()).map(series::view).orElse(null)))
                .toList()));
    }

    public record SweepView(RunSweep sweep, List<Point> points) {
        public record Point(int position, Map<String, Object> parameters, SeriesView series) { }
    }
}
