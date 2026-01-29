package com.pnomeer.pipeline.api;

import com.pnomeer.pipeline.PipelineRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class IngestionJobState {
    private static final int MAX_PROGRESS_POINTS = 200;

    private final String jobId;
    private volatile IngestionJobStatus status;
    private volatile int producedCount;
    private volatile int insertedCount;
    private volatile int validationErrorCount;
    private volatile Map<String, Long> errorSummary;
    private volatile String failureMessage;
    private volatile PipelineRunner.ProgressSnapshot latestProgress;
    private final CopyOnWriteArrayList<PipelineRunner.ProgressSnapshot> progressTimeline = new CopyOnWriteArrayList<>();

    public IngestionJobState(String jobId) {
        this.jobId = jobId;
        this.status = IngestionJobStatus.RUNNING;
        this.errorSummary = Map.of();
    }

    public String getJobId() {
        return jobId;
    }

    public IngestionJobStatus getStatus() {
        return status;
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
        return failureMessage;
    }

    public PipelineRunner.ProgressSnapshot getLatestProgress() {
        return latestProgress;
    }

    public List<PipelineRunner.ProgressSnapshot> getProgressTimeline() {
        return new ArrayList<>(progressTimeline);
    }

    public void recordProgress(PipelineRunner.ProgressSnapshot progressSnapshot) {
        this.latestProgress = progressSnapshot;
        progressTimeline.add(progressSnapshot);
        if (progressTimeline.size() > MAX_PROGRESS_POINTS) {
            progressTimeline.removeFirst();
        }
    }

    public void markSucceeded(int producedCount, int insertedCount, int validationErrorCount, Map<String, Long> errorSummary) {
        this.producedCount = producedCount;
        this.insertedCount = insertedCount;
        this.validationErrorCount = validationErrorCount;
        this.errorSummary = errorSummary;
        this.failureMessage = null;
        this.status = IngestionJobStatus.SUCCEEDED;
    }

    public void markFailed(String message) {
        this.failureMessage = message;
        this.status = IngestionJobStatus.FAILED;
    }
}
