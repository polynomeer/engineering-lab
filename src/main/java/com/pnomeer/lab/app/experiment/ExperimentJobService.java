package com.pnomeer.lab.app.experiment;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.runtime.DurableExperimentRunner;
import com.pnomeer.lab.storage.StorageProperties;
import com.pnomeer.lab.experiments.concurrency.*;
import com.pnomeer.lab.experiments.io.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class ExperimentJobService {
    private final DurableExperimentRunner runner;
    private final RunStore store;
    private final StorageProperties properties;
    private final QueueContentionExperiment queueExperiment;
    private final IoEndpointComparisonExperiment ioExperiment;
    private final TaskExecutor executor;

    public ExperimentJobService(DurableExperimentRunner runner, RunStore store, StorageProperties properties,
            QueueContentionExperiment queueExperiment, IoEndpointComparisonExperiment ioExperiment,
            @Qualifier("experimentTaskExecutor") TaskExecutor executor) {
        this.runner = runner; this.store = store; this.properties = properties;
        this.queueExperiment = queueExperiment; this.ioExperiment = ioExperiment; this.executor = executor;
    }

    public String startQueueContentionJob(QueueContentionScenario scenario) { return submit(queueExperiment, scenario); }
    public String startIoEndpointComparisonJob(IoEndpointComparisonScenario scenario) { return submit(ioExperiment, scenario); }

    private <C extends ExperimentScenario> String submit(Experiment<C> experiment, C scenario) {
        var run = runner.prepare(experiment, scenario, UUID.randomUUID().toString());
        try { executor.execute(() -> runner.runPrepared(experiment, scenario, run)); }
        catch (org.springframework.core.task.TaskRejectedException ex) {
            runner.reject(run, "Executor rejected the submitted run");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Experiment queue is full or shutting down", ex);
        }
        return run.runId();
    }

    public ExperimentJobState getJob(String id) {
        return store.find(id).filter(run -> !run.experimentType().equals("pipeline.excel-ingestion"))
                .map(run -> new ExperimentJobState(run,
                        store.recentMetrics(id, properties.previewLimit()).stream().map(StoredMetric::point).toList(),
                        firstMetric(id))).orElse(null);
    }

    public List<ExperimentJobState> listJobs() { return listJobs(Math.min(100, properties.pageLimit()), Long.MAX_VALUE); }
    public List<ExperimentJobState> listJobs(int limit, long before) {
        var runs = new ArrayList<StoredRun>();
        runs.addAll(store.list(queueExperiment.type(), before, limit));
        runs.addAll(store.list(ioExperiment.type(), before, limit));
        return runs.stream().sorted(Comparator.comparingLong(StoredRun::sequence).reversed()).limit(limit)
                .map(run -> new ExperimentJobState(run, List.of(), firstMetric(run.runId()))).toList();
    }

    private long firstMetric(String id) {
        return store.metrics(id, -1, 1).stream().mapToLong(metric -> metric.point().timestampEpochMs()).findFirst().orElse(0);
    }
}
