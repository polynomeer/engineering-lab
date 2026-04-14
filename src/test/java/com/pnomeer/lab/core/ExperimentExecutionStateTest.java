package com.pnomeer.lab.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentExecutionStateTest {
    @Test
    void tracksLifecycleTransitions() {
        ExperimentExecutionState state = new ExperimentExecutionState("job-1", "pipeline.excel", "PIPELINE");

        state.markStarted();
        state.recordMetricPublished();
        state.markSucceeded();

        assertEquals("job-1", state.getJobId());
        assertEquals("pipeline.excel", state.getExperimentType());
        assertEquals("PIPELINE", state.getScenarioId());
        assertEquals(ExperimentStatus.SUCCEEDED, state.getStatus());
        assertTrue(state.getStartedAtEpochMs() >= state.getCreatedAtEpochMs());
        assertTrue(state.getFirstMetricAtEpochMs() >= state.getStartedAtEpochMs());
        assertTrue(state.getCompletedAtEpochMs() >= state.getStartedAtEpochMs());
    }

    @Test
    void capturesFailureMessage() {
        ExperimentExecutionState state = new ExperimentExecutionState("job-2", "queue.contention", "baseline");

        state.markStarted();
        state.markFailed("deadlock detected");

        assertEquals(ExperimentStatus.FAILED, state.getStatus());
        assertEquals("deadlock detected", state.getFailureMessage());
        assertTrue(state.getCompletedAtEpochMs() >= state.getStartedAtEpochMs());
    }
}
