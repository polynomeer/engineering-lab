package com.pnomeer.lab.core.storage;

import com.pnomeer.lab.core.ExperimentSummary;
import com.pnomeer.lab.core.RunMetadata;
import java.util.List;
import java.util.Optional;

public interface RunStore {
    StoredRun create(String experimentType, String scenarioId, RunMetadata metadata);
    void start(String runId);
    void complete(String runId, RunStatus status, ExperimentSummary summary, String failure);
    Optional<StoredRun> find(String runId);
    /** Newest first; beforeSequence is exclusive. Null type means all experiments. */
    List<StoredRun> list(String experimentType, long beforeSequence, int limit);
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
    int recoverInterrupted();
    List<ArtifactReference> artifacts(String runId);
    void registerArtifact(ArtifactReference reference);
}
