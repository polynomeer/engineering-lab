package com.pnomeer.lab.app.run;

import com.pnomeer.lab.core.storage.*;
import java.util.*;

/** Side-by-side evidence, never an automatic claim of causation or improvement. */
public record RunComparison(StoredRun baseline, StoredRun candidate, List<String> cautions,
        List<Difference> conditions, List<Value> values) {
    public record Difference(String field, Object baseline, Object candidate, boolean different) { }
    public record Value(String kind, String name, String unit, Number baseline, Number candidate) { }

    public static RunComparison of(StoredRun a, StoredRun b) {
        List<String> cautions = new ArrayList<>();
        if (!a.experimentType().equals(b.experimentType())) cautions.add("Different experiments: metric names alone do not establish comparable semantics.");
        if (a.status() != RunStatus.SUCCEEDED || b.status() != RunStatus.SUCCEEDED) cautions.add("One or both runs are not successful completed measurements.");
        if (a.runId().equals(b.runId())) cautions.add("The same Run is selected twice; this is not a repeated measurement.");
        if (a.runMetadata().git().commit() == null || b.runMetadata().git().commit() == null
                || !Boolean.FALSE.equals(a.runMetadata().git().dirty()) || !Boolean.FALSE.equals(b.runMetadata().git().dirty())) {
            cautions.add("Git provenance is unknown or dirty; the commit alone cannot reproduce these runs.");
        }
        List<Difference> conditions = new ArrayList<>();
        add(conditions, "experiment", a.experimentType(), b.experimentType());
        add(conditions, "scenario", a.scenarioId(), b.scenarioId());
        add(conditions, "environment", a.runMetadata().environment(), b.runMetadata().environment());
        add(conditions, "git", a.runMetadata().git(), b.runMetadata().git());
        var keys = new TreeSet<>(a.runMetadata().parameters().keySet()); keys.addAll(b.runMetadata().parameters().keySet());
        keys.remove("scenario.definition"); // Canonical definition remains in each complete Run payload.
        for (String key : keys) add(conditions, "parameters." + key, a.runMetadata().parameters().get(key), b.runMetadata().parameters().get(key));
        if (conditions.stream().anyMatch(Difference::different)) cautions.add("Recorded conditions differ. Account for these differences before interpreting the measurements.");
        if (!a.runMetadata().parameters().containsKey("measurement.mode") || !b.runMetadata().parameters().containsKey("measurement.mode")) cautions.add("Measurement mode is not recorded for one or both legacy runs.");
        if ((a.experimentType().equals("pipeline.excel-ingestion") || b.experimentType().equals("pipeline.excel-ingestion"))
                && (!a.runMetadata().parameters().containsKey("input.sha256") || !b.runMetadata().parameters().containsKey("input.sha256"))) cautions.add("Input checksum is missing; identical input cannot be established.");
        cautions.add("Single runs are observations, not statistical evidence. Unknown units are not inferred or converted.");
        List<Value> values = new ArrayList<>();
        metrics(values, "counter", a.summary().counters(), b.summary().counters());
        metrics(values, "gauge", a.summary().gauges(), b.summary().gauges());
        return new RunComparison(a, b, List.copyOf(cautions), List.copyOf(conditions), List.copyOf(values));
    }
    private static void add(List<Difference> target, String key, Object a, Object b) {
        target.add(new Difference(key, a, b, !Objects.equals(a, b)));
    }
    private static void metrics(List<Value> target, String kind, Map<String, ? extends Number> a, Map<String, ? extends Number> b) {
        var keys = new TreeSet<>(a.keySet()); keys.addAll(b.keySet());
        for (String key : keys) target.add(new Value(kind, key, unit(key), a.get(key), b.get(key)));
    }
    // Explicit known summary units only. New metrics must declare semantics before comparison.
    public static String unit(String key) {
        return com.pnomeer.lab.metrics.MetricCatalog.unit(key);
    }
}
