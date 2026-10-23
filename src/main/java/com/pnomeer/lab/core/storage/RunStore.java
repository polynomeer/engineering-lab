package com.pnomeer.lab.core.storage;

import com.pnomeer.lab.core.ExperimentSummary;
import com.pnomeer.lab.core.RunMetadata;
import java.util.List;
import java.util.Optional;

public interface RunStore {
    StoredRun create(String experimentType, String scenarioId, RunMetadata metadata);
    /** Atomically create the Run and its execution definition. */
    default StoredRun create(String experimentType, String scenarioId, RunMetadata metadata, RunExecution.Definition execution) {
        throw new UnsupportedOperationException("Atomic execution creation unavailable");
    }
    void start(String runId);
    void complete(String runId, RunStatus status, ExperimentSummary summary, String failure);
    Optional<StoredRun> find(String runId);
    /** Newest first; beforeSequence is exclusive. Null type means all experiments. */
    List<StoredRun> list(String experimentType, long beforeSequence, int limit);
    /** Newest first; null filters match everything. {@code scenarioPrefix} matches IDs that start with it. */
    default List<StoredRun> list(String experimentType, String scenarioPrefix, RunStatus status, long beforeSequence, int limit) {
        if (scenarioPrefix == null && status == null) return list(experimentType, beforeSequence, limit);
        throw new UnsupportedOperationException("Filtered listing unavailable");
    }
    void appendMetrics(String runId, List<StoredMetric> metrics);
    /** Oldest first; afterSequence is exclusive, starting at -1. */
    List<StoredMetric> metrics(String runId, long afterSequence, int limit);
    List<StoredMetric> recentMetrics(String runId, int limit);
    /** Newest first, exclusive cursor for bounded backwards scans. */
    List<StoredMetric> metricsBefore(String runId, long beforeSequence, int limit);
    default void appendDeadLetter(String runId, com.pnomeer.lab.core.DeadLetter row) {
        throw new UnsupportedOperationException("Dead-letter persistence unavailable");
    }
    default List<com.pnomeer.lab.core.DeadLetter> deadLetters(String runId, int afterRow, int limit) {
        throw new UnsupportedOperationException("Dead-letter persistence unavailable");
    }
    default void registerExecution(String runId, RunExecution.Definition definition) {
        throw new UnsupportedOperationException("Execution persistence unavailable");
    }
    default Optional<RunExecution> execution(String runId) {
        throw new UnsupportedOperationException("Execution persistence unavailable");
    }
    default void registerWorker(String runId, RunExecution.Worker worker) {
        throw new UnsupportedOperationException("Worker persistence unavailable");
    }
    default void advanceExecution(String runId, RunExecution.Phase phase) {
        throw new UnsupportedOperationException("Execution persistence unavailable");
    }
    /** Caller must verify producer termination, final result, counts and durable flush before sealing. */
    default void finishExecutionEvidence(String runId) {
        throw new UnsupportedOperationException("Execution persistence unavailable");
    }
    default void appendCheck(String runId, RunCheck check) {
        throw new UnsupportedOperationException("Check persistence unavailable");
    }
    /** Oldest first, exclusive cursor from -1. Complete scans require finished producers. */
    default List<RunCheck> checks(String runId, long afterSequence, int limit) {
        throw new UnsupportedOperationException("Check persistence unavailable");
    }
    int recoverInterrupted();
    List<ArtifactReference> artifacts(String runId);
    void registerArtifact(ArtifactReference reference);
}
