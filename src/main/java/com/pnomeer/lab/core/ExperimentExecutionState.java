package com.pnomeer.lab.core;

public final class ExperimentExecutionState {
    private final String jobId;
    private final String experimentType;
    private final String scenarioId;
    private final long createdAtEpochMs;
    private volatile long updatedAtEpochMs;
    private volatile long startedAtEpochMs;
    private volatile long completedAtEpochMs;
    private volatile long firstMetricAtEpochMs;
    private volatile ExperimentStatus status;
    private volatile String failureMessage;

    public ExperimentExecutionState(String jobId, String experimentType, String scenarioId) {
        this.jobId = jobId;
        this.experimentType = experimentType;
        this.scenarioId = scenarioId;
        this.createdAtEpochMs = System.currentTimeMillis();
        this.updatedAtEpochMs = this.createdAtEpochMs;
        this.status = ExperimentStatus.RUNNING;
    }

    public String getJobId() {
        return jobId;
    }

    public String getExperimentType() {
        return experimentType;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public long getCreatedAtEpochMs() {
        return createdAtEpochMs;
    }

    public long getUpdatedAtEpochMs() {
        return updatedAtEpochMs;
    }

    public long getStartedAtEpochMs() {
        return startedAtEpochMs;
    }

    public long getCompletedAtEpochMs() {
        return completedAtEpochMs;
    }

    public long getFirstMetricAtEpochMs() {
        return firstMetricAtEpochMs;
    }

    public ExperimentStatus getStatus() {
        return status;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void markStarted() {
        this.startedAtEpochMs = System.currentTimeMillis();
        this.updatedAtEpochMs = this.startedAtEpochMs;
    }

    public void recordMetricPublished() {
        if (this.firstMetricAtEpochMs == 0L) {
            this.firstMetricAtEpochMs = System.currentTimeMillis();
        }
        this.updatedAtEpochMs = System.currentTimeMillis();
    }

    public void markSucceeded() {
        this.failureMessage = null;
        this.status = ExperimentStatus.SUCCEEDED;
        this.completedAtEpochMs = System.currentTimeMillis();
        this.updatedAtEpochMs = this.completedAtEpochMs;
    }

    public void markFailed(String failureMessage) {
        this.failureMessage = failureMessage;
        this.status = ExperimentStatus.FAILED;
        this.completedAtEpochMs = System.currentTimeMillis();
        this.updatedAtEpochMs = this.completedAtEpochMs;
    }
}
