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
    public static RunComparison of(StoredRun a, StoredRun b, RunExecution ae, RunExecution be) {
        var base = of(a, b);
        if (ae == null && be == null) return base;
        var conditions = new ArrayList<>(base.conditions());
        var cautions = new ArrayList<>(base.cautions());
        add(conditions, "execution.definition", ae == null ? null : ae.definition(), be == null ? null : be.definition());
        add(conditions, "execution.phase", ae == null ? null : ae.phase(), be == null ? null : be.phase());
        add(conditions, "execution.evidenceComplete", ae == null ? null : ae.evidenceComplete(), be == null ? null : be.evidenceComplete());
        add(conditions, "execution.environment", environment(a, ae), environment(b, be));
        add(conditions, "execution.git", git(a, ae), git(b, be));
        add(conditions, "execution.workerArtifactSha256", artifact(ae), artifact(be));
        if (ae == null || be == null) cautions.add("Execution evidence is not recorded for one of these legacy runs; its execution target is unknown.");
        if (ae != null && !ae.evidenceComplete() || be != null && !be.evidenceComplete()) cautions.add("Execution evidence is incomplete; missing worker or final evidence must not be treated as a complete measurement.");
        if (unknownWorker(ae) || unknownWorker(be)) cautions.add("Worker identity is unknown. Controller environment is not a substitute for worker measurements.");
        if (dirtyWorker(ae) || dirtyWorker(be)) cautions.add("Worker Git provenance is unknown or dirty; controller Git does not identify the executed worker code.");
        if (conditions.subList(base.conditions().size(), conditions.size()).stream().anyMatch(Difference::different)) {
            cautions.add("Execution target, worker artifact, measurement scope or evidence completeness differ. Account for these differences before comparing results.");
        }
        return new RunComparison(a, b, List.copyOf(cautions), List.copyOf(conditions), base.values());
    }
    private static boolean external(RunExecution execution) {
        return execution != null && execution.definition().kind() != RunExecution.Kind.IN_PROCESS;
    }
    private static boolean unknownWorker(RunExecution execution) { return external(execution) && execution.worker() == null; }
    private static boolean dirtyWorker(RunExecution execution) {
        return external(execution) && execution.worker() != null
                && (execution.worker().git().commit() == null || !Boolean.FALSE.equals(execution.worker().git().dirty()));
    }
    private static Object environment(StoredRun run, RunExecution execution) {
        if (execution == null) return null;
        return external(execution) ? execution.worker() == null ? null : execution.worker().environment() : run.runMetadata().environment();
    }
    private static Object git(StoredRun run, RunExecution execution) {
        if (execution == null) return null;
        return external(execution) ? execution.worker() == null ? null : execution.worker().git() : run.runMetadata().git();
    }
    private static String artifact(RunExecution execution) {
        return execution == null || execution.worker() == null ? null : execution.worker().artifactSha256();
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
