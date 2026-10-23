package com.pnomeer.lab.app.series;

import com.pnomeer.lab.core.RunEnvironment;
import com.pnomeer.lab.core.storage.RunSeries;
import com.pnomeer.lab.core.storage.RunStatus;

import java.util.*;

/**
 * A pinned baseline carried outside the store (e.g. committed for CI): the raw measured values per measure,
 * so statistics and range separation are recomputed exactly as for a stored series.
 */
public record BaselineFile(int schemaVersion, String experimentType, String scenarioId, String definitionSha256,
        String sourceSeriesId, long createdAtEpochMs, String gitCommit, RunEnvironment environment, List<Measure> measures) {
    public static final int SCHEMA_VERSION = 1;
    public record Measure(String role, String name, String unit, String kind, List<Double> values) {
        public Measure {
            Objects.requireNonNull(role); Objects.requireNonNull(name); Objects.requireNonNull(unit);
            values = List.copyOf(values == null ? List.of() : values);
            if (values.size() > RunSeries.MAX_REPETITIONS) throw new IllegalArgumentException("Too many baseline values");
        }
    }
    public BaselineFile {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported baseline file version");
        Objects.requireNonNull(experimentType); Objects.requireNonNull(scenarioId); Objects.requireNonNull(definitionSha256);
        measures = List.copyOf(measures == null ? List.of() : measures);
        if (measures.isEmpty() || measures.size() > 32) throw new IllegalArgumentException("A baseline file needs 1..32 measures");
    }

    /** The file as a finished series view, so comparisons and regression checks treat it like a stored baseline. */
    SeriesView view() {
        int count = measures.stream().mapToInt(m -> m.values().size()).max().orElse(0);
        var series = new RunSeries(0, "file:" + sourceSeriesId, experimentType, scenarioId, definitionSha256,
                Math.max(1, count), 0, RunStatus.SUCCEEDED, createdAtEpochMs, createdAtEpochMs, null);
        var rows = measures.stream().map(m -> new SeriesView.Measure(m.role(), m.name(), m.unit(), m.kind(), SeriesStatistics.of(m.values()))).toList();
        return new SeriesView(series, List.of(), count, rows, List.of(), null, List.of());
    }

    /** Environment facts that differ between this baseline and the candidate's first measured run. */
    List<String> environmentDifferences(RunEnvironment candidate) {
        if (environment == null || candidate == null) return List.of("Baseline or candidate environment is unknown");
        var differences = new ArrayList<String>();
        if (environment.availableProcessors() != candidate.availableProcessors())
            differences.add("CPU count " + environment.availableProcessors() + " → " + candidate.availableProcessors());
        if (environment.maxHeapBytes() != candidate.maxHeapBytes())
            differences.add("max heap " + environment.maxHeapBytes() + " → " + candidate.maxHeapBytes() + " bytes");
        if (!Objects.equals(environment.javaVersion(), candidate.javaVersion()))
            differences.add("Java " + environment.javaVersion() + " → " + candidate.javaVersion());
        if (!Objects.equals(environment.osName(), candidate.osName()) || !Objects.equals(environment.osArch(), candidate.osArch()))
            differences.add("OS " + environment.osName() + "/" + environment.osArch() + " → " + candidate.osName() + "/" + candidate.osArch());
        return differences;
    }
}
