package com.pnomeer.lab.app.ingest;

import com.pnomeer.lab.core.ExperimentExecutionState;
import com.pnomeer.lab.core.ExperimentStatus;
import com.pnomeer.lab.core.MetricPoint;
import com.pnomeer.lab.experiments.pipeline.PipelineRunner;
import com.pnomeer.lab.metrics.ExecutionMetricsSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class IngestionJobState {
    private static final int MAX_PROGRESS_POINTS = 200;
    private static final String EXPERIMENT_TYPE = "pipeline.excel-ingestion";

    private final String fileName;
    private final PipelineRunner.RunMode runMode;
    private final ExperimentExecutionState executionState;
    private volatile int producedCount;
    private volatile int insertedCount;
    private volatile int validationErrorCount;
    private volatile Map<String, Long> errorSummary;
    private volatile boolean oomExists;
    private volatile boolean dbDeadlock;
    private volatile ExecutionMetricsSnapshot latestProgress;
    private final CopyOnWriteArrayList<ExecutionMetricsSnapshot> progressTimeline = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MetricPoint> metricTimeline = new CopyOnWriteArrayList<>();

    public IngestionJobState(String jobId, String fileName, PipelineRunner.RunMode runMode) {
        this.fileName = fileName;
        this.runMode = runMode;
        this.executionState = new ExperimentExecutionState(jobId, EXPERIMENT_TYPE, runMode.name());
        this.errorSummary = Map.of();
    }

    public String getJobId() {
        return executionState.getJobId();
    }

    public IngestionJobStatus getStatus() {
        return toIngestionStatus(executionState.getStatus());
    }

    public String getFileName() {
        return fileName;
    }

    public PipelineRunner.RunMode getRunMode() {
        return runMode;
    }

    public long getCreatedAtEpochMs() {
        return executionState.getCreatedAtEpochMs();
    }

    public long getUpdatedAtEpochMs() {
        return executionState.getUpdatedAtEpochMs();
    }

    public long getStartedAtEpochMs() {
        return executionState.getStartedAtEpochMs();
    }

    public long getCompletedAtEpochMs() {
        return executionState.getCompletedAtEpochMs();
    }

    public long getFirstProgressAtEpochMs() {
        return executionState.getFirstMetricAtEpochMs();
    }

    public String getExperimentType() {
        return executionState.getExperimentType();
    }

    public String getScenarioId() {
        return executionState.getScenarioId();
    }

    public int getProducedCount() {
        return producedCount;
    }

    public int getInsertedCount() {
        return insertedCount;
    }

    public int getValidationErrorCount() {
        return validationErrorCount;
    }

    public Map<String, Long> getErrorSummary() {
        return errorSummary;
    }

    public String getFailureMessage() {
        return executionState.getFailureMessage();
    }

    public boolean isOomExists() {
        return oomExists;
    }

    public boolean isDbDeadlock() {
        return dbDeadlock;
    }

    public ExecutionMetricsSnapshot getLatestProgress() {
        return latestProgress;
    }

    public List<ExecutionMetricsSnapshot> getProgressTimeline() {
        return new ArrayList<>(progressTimeline);
    }

    public List<MetricPoint> getMetricTimeline() {
        return new ArrayList<>(metricTimeline);
    }

    public void markStarted() {
        executionState.markStarted();
    }

    public void recordProgress(ExecutionMetricsSnapshot progressSnapshot) {
        executionState.recordMetricPublished();
        this.latestProgress = progressSnapshot;
        progressTimeline.add(progressSnapshot);
        metricTimeline.add(PipelineMetricPointMapper.fromProgressSnapshot(progressSnapshot));
        if (progressTimeline.size() > MAX_PROGRESS_POINTS) {
            progressTimeline.removeFirst();
            metricTimeline.removeFirst();
        }
    }

    public void markSucceeded(int producedCount, int insertedCount, int validationErrorCount, Map<String, Long> errorSummary) {
        this.producedCount = producedCount;
        this.insertedCount = insertedCount;
        this.validationErrorCount = validationErrorCount;
        this.errorSummary = errorSummary;
        this.oomExists = false;
        this.dbDeadlock = false;
        executionState.markSucceeded();
    }

    public void markFailed(String message, boolean oomExists, boolean dbDeadlock) {
        this.oomExists = oomExists;
        this.dbDeadlock = dbDeadlock;
        executionState.markFailed(message);
    }

    private static IngestionJobStatus toIngestionStatus(ExperimentStatus status) {
        return switch (status) {
            case RUNNING -> IngestionJobStatus.RUNNING;
            case SUCCEEDED -> IngestionJobStatus.SUCCEEDED;
            case FAILED -> IngestionJobStatus.FAILED;
        };
    }
}
