package com.pnomeer.lab.app.ingest;

import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.runtime.DurableExperimentRunner;
import com.pnomeer.lab.storage.StorageProperties;
import com.pnomeer.lab.experiments.pipeline.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

@Service
public class IngestionService {
    private final DurableExperimentRunner runner;
    private final RunStore store;
    private final StorageProperties properties;
    private final PipelineExperiment experiment;
    private final TaskExecutor executor;

    public IngestionService(DurableExperimentRunner runner, RunStore store, StorageProperties properties,
            PipelineExperiment experiment, @Qualifier("ingestTaskExecutor") TaskExecutor executor) {
        this.runner = runner; this.store = store; this.properties = properties;
        this.experiment = experiment; this.executor = executor;
    }

    public String startJob(MultipartFile file, PipelineRunner.RunMode mode) {
        byte[] payload;
        try { payload = file.getBytes(); }
        catch (IOException ex) { throw new UncheckedIOException("Failed to read upload", ex); }
        String fileName = file.getOriginalFilename() == null ? "unknown.xlsx" : file.getOriginalFilename();
        var scenario = new PipelineExperimentScenario(payload, mode, progress -> { }, new PipelineExperimentCapture(), fileName);
        var run = runner.prepare(experiment, scenario, UUID.randomUUID().toString());
        try { executor.execute(() -> runner.runPrepared(experiment, scenario, run)); }
        catch (org.springframework.core.task.TaskRejectedException ex) {
            runner.reject(run, "Executor rejected the submitted run");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Ingestion queue is full or shutting down", ex);
        }
        return run.runId();
    }

    public IngestionJobState getJob(String id) {
        return store.find(id).filter(run -> run.experimentType().equals(experiment.type()))
                .map(run -> new IngestionJobState(run,
                        com.pnomeer.lab.runtime.ExperimentMetricPreview.read(store, id, properties.previewLimit()), firstMetric(id)))
                .orElse(null);
    }
    public List<IngestionJobState> listJobs() { return listJobs(Math.min(100, properties.pageLimit()), Long.MAX_VALUE); }
    public List<IngestionJobState> listJobs(int limit, long before) {
        return store.list(experiment.type(), before, limit).stream()
                .map(run -> new IngestionJobState(run,
                        com.pnomeer.lab.runtime.ExperimentMetricPreview.read(store, run.runId(), 1), firstMetric(run.runId())))
                .toList();
    }
    private long firstMetric(String id) {
        return store.metrics(id, -1, 1).stream().mapToLong(metric -> metric.point().timestampEpochMs()).findFirst().orElse(0);
    }
}
