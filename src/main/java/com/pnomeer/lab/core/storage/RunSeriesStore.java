package com.pnomeer.lab.core.storage;

import java.util.List;
import java.util.Optional;

/** Durable series membership; Run rows themselves stay unchanged. */
public interface RunSeriesStore {
    RunSeries createSeries(String seriesId, String experimentType, String scenarioId, String definitionSha256, int repetitions, int warmup);
    void startSeries(String seriesId);
    void addSeriesRun(String seriesId, RunSeries.Member member);
    void completeSeries(String seriesId, RunStatus status, String failure);
    Optional<RunSeries> findSeries(String seriesId);
    /** Ordered by position. */
    List<RunSeries.Member> seriesRuns(String seriesId);
    /** Newest first; null type means all experiments. */
    List<RunSeries> listSeries(String experimentType, int limit);
    /** Replaces the pinned baseline for the series' experiment and scenario ID. */
    void pinBaseline(String seriesId);
    Optional<RunSeries> baseline(String experimentType, String scenarioId);
    /** Pinned baselines, most recently pinned first. */
    List<RunSeries> baselines(int limit);
    RunSweep createSweep(String sweepId, String experimentType, String scenarioId, java.util.Map<String, List<Object>> grid, int repetitions, int warmup);
    void startSweep(String sweepId);
    void addSweepPoint(String sweepId, RunSweep.Point point);
    void completeSweep(String sweepId, RunStatus status, String failure);
    Optional<RunSweep> findSweep(String sweepId);
    /** Ordered by position. */
    List<RunSweep.Point> sweepPoints(String sweepId);
    List<RunSweep> listSweeps(String experimentType, int limit);
    /** Marks unfinished series and sweeps INTERRUPTED after a restart; never resumes them. */
    int recoverInterruptedSeries();
}
