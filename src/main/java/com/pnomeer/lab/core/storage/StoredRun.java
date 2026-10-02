package com.pnomeer.lab.core.storage;

import com.pnomeer.lab.core.ExperimentSummary;
import com.pnomeer.lab.core.RunMetadata;

public record StoredRun(long sequence, String runId, String experimentType, String scenarioId,
        RunStatus status, long createdAtEpochMs, long startedAtEpochMs, long completedAtEpochMs,
        RunMetadata runMetadata, ExperimentSummary summary, String failureMessage) { }
