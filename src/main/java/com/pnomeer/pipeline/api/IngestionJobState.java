package com.pnomeer.pipeline.api;

import java.util.Map;

public final class IngestionJobState {
    private final String jobId;
    private volatile IngestionJobStatus status;
    private volatile int producedCount;
    private volatile int insertedCount;
    private volatile int validationErrorCount;
    private volatile Map<String, Long> errorSummary;
    private volatile String failureMessage;

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
