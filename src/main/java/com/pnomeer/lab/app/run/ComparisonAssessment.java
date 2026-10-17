package com.pnomeer.lab.app.run;

import com.pnomeer.lab.core.GitProvenance;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.registry.measurement.MeasurementContract;
import java.util.*;

/** Recorded compatibility only; does not decide causation, property satisfaction or statistical significance. */
public record ComparisonAssessment(int schemaVersion, String baselineId, String candidateId, State state,
        MeasurementContract baselineContract, MeasurementContract candidateContract,
        List<Condition> conditions, List<MeasurePair> measures) {
    public enum State { RECORDED_CONDITIONS_MATCH, REVIEW_REQUIRED, INSUFFICIENT_EVIDENCE }
    public enum Match { MATCH, MISMATCH, UNKNOWN }
    public record Condition(String field, Match status) { }
    public record MeasurePair(String role, MeasurementContract.Measure baseline, MeasurementContract.Measure candidate,
            Number baselineValue, Number candidateValue, Match status) { }
    public static ComparisonAssessment of(StoredRun a, StoredRun b, RunExecution ae, RunExecution be) {
        var ac = contract(a); var bc = contract(b);
        var conditions = new ArrayList<Condition>();
        compare(conditions, "contract", ac == null ? null : ac.methodVersion(), bc == null ? null : bc.methodVersion());
        compare(conditions, "experiment", a.experimentType(), b.experimentType());
        conditions.add(new Condition("distinct-runs", a.runId().equals(b.runId()) ? Match.MISMATCH : Match.MATCH));
        conditions.add(new Condition("successful-complete-evidence", a.status() == RunStatus.SUCCEEDED && b.status() == RunStatus.SUCCEEDED
                && ae != null && be != null && ae.evidenceComplete() && be.evidenceComplete() ? Match.MATCH : Match.UNKNOWN));
        compare(conditions, "execution", ae == null ? null : ae.definition(), be == null ? null : be.definition());
        compare(conditions, "environment", environment(a, ae), environment(b, be));
        compare(conditions, "git", cleanGit(a, ae), cleanGit(b, be));
        if (external(ae) || external(be)) compare(conditions, "worker-artifact", artifact(ae), artifact(be));
        // JVM/OS identity is not a database/broker/host-load fingerprint.
        if (external(ae) || external(be) || a.experimentType().equals("pipeline.excel-ingestion") || b.experimentType().equals("pipeline.excel-ingestion"))
            conditions.add(new Condition("external-dependency-environment", Match.UNKNOWN));
        var keys = new TreeSet<String>();
        a.runMetadata().parameters().keySet().stream().limit(257).forEach(keys::add);
        b.runMetadata().parameters().keySet().stream().limit(257).forEach(keys::add);
        if (a.runMetadata().parameters().size() > 256 || b.runMetadata().parameters().size() > 256)
            conditions.add(new Condition("parameter-source-limit", Match.UNKNOWN));
        if (ac != null) keys.addAll(ac.requiredParameters());
        if (bc != null) keys.addAll(bc.requiredParameters());
        keys.remove(MeasurementContract.PARAMETER);
        keys.remove("scenario.definition"); // The normalized parameter values remain compared; hash remains a condition.
        int count = 0;
        for (String key : keys) {
            if (++count > 256) { conditions.add(new Condition("parameter-limit", Match.UNKNOWN)); break; }
            compare(conditions, "parameters." + key, a.runMetadata().parameters().get(key), b.runMetadata().parameters().get(key));
        }
        if (ac != null && bc != null) {
            compare(conditions, "verification-properties", ac.properties(), bc.properties());
            compare(conditions, "required-parameters", ac.requiredParameters(), bc.requiredParameters());
            compare(conditions, "limitations", ac.limitations(), bc.limitations());
        }
        var am = measures(ac); var bm = measures(bc);
        var roles = new TreeSet<>(am.keySet()); roles.addAll(bm.keySet());
        var pairs = new ArrayList<MeasurePair>();
        for (String role : roles) {
            var x = am.get(role); var y = bm.get(role);
            var xv = value(a, x); var yv = value(b, y);
            compare(conditions, "measure." + role + ".unit", x == null ? null : x.unit(), y == null ? null : y.unit());
            compare(conditions, "measure." + role + ".scope", x == null ? null : x.scope(), y == null ? null : y.scope());
            compare(conditions, "measure." + role + ".semantics", x == null ? null : x.semantics(), y == null ? null : y.semantics());
            compare(conditions, "measure." + role + ".window", x == null ? null : x.window(), y == null ? null : y.window());
            Match match = match(x, y);
            if (match == Match.MATCH && (xv == null || yv == null || unknown(x.unit()) || unknown(x.scope())
                    || unknown(x.semantics()) || unknown(x.window()))) match = Match.UNKNOWN;
            pairs.add(new MeasurePair(role, x, y, xv, yv, match));
            conditions.add(new Condition("measure." + role, match));
        }
        if (ac == null || bc == null || ac.measures().isEmpty() || bc.measures().isEmpty())
            conditions.add(new Condition("performance-measures-not-declared", Match.UNKNOWN));
        State state = conditions.stream().anyMatch(c -> c.status() == Match.MISMATCH) ? State.REVIEW_REQUIRED
                : conditions.stream().anyMatch(c -> c.status() == Match.UNKNOWN) ? State.INSUFFICIENT_EVIDENCE : State.RECORDED_CONDITIONS_MATCH;
        return new ComparisonAssessment(1, a.runId(), b.runId(), state, ac, bc, List.copyOf(conditions), List.copyOf(pairs));
    }
    private static MeasurementContract contract(StoredRun run) {
        return MeasurementContract.read(run.runMetadata().parameters().get(MeasurementContract.PARAMETER), run.experimentType()).orElse(null);
    }
    private static Map<String, MeasurementContract.Measure> measures(MeasurementContract contract) {
        var result = new TreeMap<String, MeasurementContract.Measure>();
        if (contract != null) contract.measures().forEach(m -> result.put(m.role(), m));
        return result;
    }
    private static Number value(StoredRun run, MeasurementContract.Measure measure) {
        if (measure == null) return null;
        Number value;
        if (measure.kind() == MeasurementContract.Kind.counter) value = run.summary().counters().get(measure.name());
        else value = run.summary().gauges().get(measure.name());
        return value == null || !Double.isFinite(value.doubleValue()) ? null : value;
    }
    private static Match match(Object a, Object b) {
        return unknown(a) || unknown(b) ? Match.UNKNOWN : Objects.equals(a, b) ? Match.MATCH : Match.MISMATCH;
    }
    private static boolean unknown(Object value) {
        return value == null || value instanceof String s && (s.isBlank() || s.equalsIgnoreCase("unknown"));
    }
    private static void compare(List<Condition> conditions, String field, Object a, Object b) { conditions.add(new Condition(field, match(a, b))); }
    private static boolean external(RunExecution execution) { return execution != null && execution.definition().kind() != RunExecution.Kind.IN_PROCESS; }
    private static Object environment(StoredRun run, RunExecution execution) {
        if (execution == null) return null;
        return external(execution) ? execution.worker() == null ? null : execution.worker().environment() : run.runMetadata().environment();
    }
    private static GitProvenance cleanGit(StoredRun run, RunExecution execution) {
        if (execution == null) return null;
        var git = external(execution) ? execution.worker() == null ? null : execution.worker().git() : run.runMetadata().git();
        return git == null || git.commit() == null || git.commit().isBlank() || !Boolean.FALSE.equals(git.dirty()) || git.error() != null ? null : git;
    }
    private static String artifact(RunExecution execution) { return execution == null || execution.worker() == null ? null : execution.worker().artifactSha256(); }
}
