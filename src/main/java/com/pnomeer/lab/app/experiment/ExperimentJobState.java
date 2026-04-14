package com.pnomeer.lab.app.experiment;

import com.pnomeer.lab.core.ExperimentExecutionState;
import com.pnomeer.lab.core.ExperimentResult;
import com.pnomeer.lab.core.ExperimentStatus;
import com.pnomeer.lab.core.ExperimentSummary;
import com.pnomeer.lab.core.MetricPoint;

import java.util.List;

public final class ExperimentJobState {
    private final ExperimentExecutionState executionState;
    private volatile ExperimentSummary summary;
    private volatile List<MetricPoint> timeline;

    public ExperimentJobState(String jobId, String experimentType, String scenarioId) {
        this.executionState = new ExperimentExecutionState(jobId, experimentType, scenarioId);
        this.summary = ExperimentSummary.empty();
        this.timeline = List.of();
    }

    public String getJobId() {
        return executionState.getJobId();
    }

    public String getExperimentType() {
        return executionState.getExperimentType();
    }

    public String getScenarioId() {
        return executionState.getScenarioId();
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

    public long getFirstMetricAtEpochMs() {
        return executionState.getFirstMetricAtEpochMs();
    }

    public ExperimentStatus getStatus() {
        return executionState.getStatus();
    }

    public String getFailureMessage() {
        return executionState.getFailureMessage();
    }

    public ExperimentSummary getSummary() {
        return summary;
    }

    public List<MetricPoint> getTimeline() {
        return timeline;
    }

    public void markStarted() {
        executionState.markStarted();
    }

    public void recordMetricPublished() {
        executionState.recordMetricPublished();
    }

    public void markCompleted(ExperimentResult result) {
        this.summary = result.summary();
        this.timeline = result.timeline();
        if (result.status() == ExperimentStatus.SUCCEEDED) {
            executionState.markSucceeded();
        } else {
            executionState.markFailed(result.failureMessage());
        }
        if (!result.timeline().isEmpty()) {
            executionState.recordMetricPublished();
        }
    }

    public void markFailed(String message) {
        executionState.markFailed(message);
    }
}
