package com.pnomeer.lab.cli;

import com.pnomeer.lab.app.series.SweepGrid;
import com.pnomeer.lab.registry.ExperimentCatalog;
import com.pnomeer.lab.scenario.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Runs suite files: one practical situation as ordered steps (a single run, a repeated series, a sweep, or a
 * comparison of two earlier series). Every step is validated before anything is submitted, and each run step
 * goes through the same code path as {@code lab run} / {@code lab sweep}. Output is JSON lines: one per step,
 * one per suite, and a final summary.
 */
final class ScenarioSuite {
    static final int SCHEMA_VERSION = 1, MAX_STEPS = 32, DEFAULT_TIMEOUT_SECONDS = 900, SUITE_LIMIT = 65536;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ID = "[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}";
    private static final Set<String> SUITE_KEYS = Set.of("schemaVersion", "suite", "steps");
    private static final Set<String> HEADER_KEYS = Set.of("id", "title", "situation", "question");
    private static final Set<String> STEP_KEYS = Set.of("id", "scenario", "repeat", "warmup", "sweep", "expect", "assert", "compare", "timeoutSeconds");

    enum Expect { SUCCEEDED, FAILED, ANY }
    enum Kind { RUN, SERIES, SWEEP, COMPARE }

    record Step(String id, Kind kind, Path scenario, int repeat, int warmup, Map<String, List<Object>> sweep, Expect expect,
            Map<String, Long> asserts, String baseline, String candidate, Integer timeoutSeconds) { }
    record Suite(Path file, String id, String title, String situation, String question, List<Step> steps) { }

    private ScenarioSuite() { }

    static int main(String[] args, PrintStream out, PrintStream err) {
        try {
            var files = new ArrayList<Path>();
            URI server = URI.create("http://localhost:8080");
            Integer timeout = null;
            Path report = null;
            boolean dryRun = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--dry-run" -> dryRun = true;
                    case "--server" -> server = URI.create(value(args, ++i));
                    case "--timeout-seconds" -> timeout = Integer.parseInt(value(args, ++i));
                    case "--report" -> report = Path.of(value(args, ++i));
                    default -> {
                        if (args[i].startsWith("--")) throw new ScenarioException("Unsupported suite option: " + args[i]);
                        files.add(Path.of(args[i]));
                    }
                }
            }
            if (files.isEmpty()) throw new ScenarioException("suite needs at least one FILE");
            if (timeout != null && (timeout < 1 || timeout > 86_400)) throw new ScenarioException("--timeout-seconds must be 1..86400");
            if (!("http".equals(server.getScheme()) || "https".equals(server.getScheme())) || server.getHost() == null
                    || server.getQuery() != null || !(server.getPath().isEmpty() || server.getPath().equals("/")))
                throw new ScenarioException("--server requires an HTTP(S) origin URL");
            // Everything is read and validated first: a typo in the last suite must not surface after an hour of runs.
            var suites = new ArrayList<Suite>();
            for (Path file : files) suites.add(validate(read(file)));
            if (dryRun) { plan(suites, out); return 0; }
            return run(suites, server, timeout, report, out, err);
        } catch (Exception ex) {
            err.println(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            return 1;
        }
    }

    private static String value(String[] args, int index) {
        if (index >= args.length) throw new ScenarioException("Missing value for " + args[index - 1]);
        return args[index];
    }

    // ---- reading and validation -------------------------------------------------------------------------

    static Suite read(Path file) throws IOException {
        Path real = file.toRealPath();
        byte[] bytes = Files.readAllBytes(real);
        if (bytes.length > SUITE_LIMIT) throw new ScenarioException("Suite file exceeds 64 KiB: " + file);
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        Object raw = new Yaml(new SafeConstructor(options)).load(new String(bytes, StandardCharsets.UTF_8));
        var root = map(raw, "suite file", SUITE_KEYS);
        if (!Integer.valueOf(SCHEMA_VERSION).equals(root.get("schemaVersion"))) throw new ScenarioException("Unsupported suite schemaVersion in " + file);
        var header = map(root.get("suite"), "suite", HEADER_KEYS);
        String id = id(header.get("id"), "suite.id");
        String title = text(header.get("title"), "suite.title", 200);
        String situation = text(header.get("situation"), "suite.situation", 2000);
        String question = text(header.get("question"), "suite.question", 1000);
        if (!(root.get("steps") instanceof List<?> rawSteps) || rawSteps.isEmpty() || rawSteps.size() > MAX_STEPS)
            throw new ScenarioException("suite.steps must list 1.." + MAX_STEPS + " steps");
        var steps = new ArrayList<Step>();
        var seen = new HashSet<String>();
        for (Object item : rawSteps) {
            var step = map(item, "step", STEP_KEYS);
            String stepId = id(step.get("id"), "step.id");
            if (!seen.add(stepId)) throw new ScenarioException("Duplicate step id: " + stepId);
            steps.add(step(stepId, step, real.getParent()));
        }
        return new Suite(real, id, title, situation, question, List.copyOf(steps));
    }

    private static Step step(String id, Map<?, ?> step, Path directory) {
        Integer timeout = step.get("timeoutSeconds") == null ? null : integer(step.get("timeoutSeconds"), id + ".timeoutSeconds", 1, 86_400);
        if (step.get("compare") != null) {
            if (step.keySet().stream().anyMatch(k -> !Set.of("id", "compare").contains(k)))
                throw new ScenarioException(id + ": a compare step takes only id and compare");
            var compare = map(step.get("compare"), id + ".compare", Set.of("baseline", "candidate"));
            return new Step(id, Kind.COMPARE, null, 0, 0, Map.of(), Expect.ANY, Map.of(),
                    reference(compare.get("baseline"), id), reference(compare.get("candidate"), id), null);
        }
        if (!(step.get("scenario") instanceof String scenario)) throw new ScenarioException(id + ": scenario is required");
        Path path = directory.resolve(scenario).normalize();
        Map<String, List<Object>> sweep = new LinkedHashMap<>();
        if (step.get("sweep") != null) {
            map(step.get("sweep"), id + ".sweep", null).forEach((k, v) -> {
                if (!(k instanceof String key) || !(v instanceof List<?> values) || values.isEmpty())
                    throw new ScenarioException(id + ".sweep needs name: [values]");
                sweep.put(key, List.copyOf(values));
            });
        }
        Integer repeat = step.get("repeat") == null ? null : integer(step.get("repeat"), id + ".repeat", 1, 50);
        int warmup = step.get("warmup") == null ? 0 : integer(step.get("warmup"), id + ".warmup", 0, 10);
        if (step.get("warmup") != null && repeat == null && sweep.isEmpty()) throw new ScenarioException(id + ": warmup needs repeat or sweep");
        Kind kind = !sweep.isEmpty() ? Kind.SWEEP : repeat != null ? Kind.SERIES : Kind.RUN;
        Expect expect = step.get("expect") == null ? Expect.SUCCEEDED : Expect.valueOf(String.valueOf(step.get("expect")));
        var asserts = new LinkedHashMap<String, Long>();
        if (step.get("assert") != null) {
            if (kind != Kind.RUN) throw new ScenarioException(id + ": assert applies to single runs only");
            map(step.get("assert"), id + ".assert", null).forEach((k, v) -> {
                if (!(k instanceof String key) || !(v instanceof Number n)) throw new ScenarioException(id + ".assert needs counter: number");
                asserts.put(key, n.longValue());
            });
        }
        return new Step(id, kind, path, kind == Kind.SWEEP && repeat == null ? 3 : repeat == null ? 1 : repeat, warmup,
                Map.copyOf(sweep), expect, Map.copyOf(asserts), null, null, timeout);
    }

    /** Checks every scenario offline, expands every sweep, and resolves every comparison reference. */
    static Suite validate(Suite suite) throws Exception {
        var catalog = ExperimentCatalog.load();
        var documents = new ScenarioDocuments(SUITE_LIMIT);
        var series = new HashMap<String, Integer>(); // step id -> points (0 for a plain series)
        for (var step : suite.steps()) {
            if (step.kind() == Kind.COMPARE) {
                for (String ref : List.of(step.baseline(), step.candidate())) resolve(ref, series, step.id());
                continue;
            }
            if (!Files.isRegularFile(step.scenario())) throw new ScenarioException(step.id() + ": scenario not found: " + step.scenario());
            ScenarioDefinition definition;
            try (var stream = Files.newInputStream(step.scenario())) {
                definition = documents.read(stream, ScenarioDocuments.format(step.scenario().toString()));
            }
            try {
                catalog.get(definition.experiment()).validate(definition);
                if (definition.input() != null) LabCli.verifyInput(step.scenario().getParent(), definition.input());
                if (step.kind() == Kind.SWEEP) {
                    var points = SweepGrid.expand(definition, step.sweep(), step.repeat(), step.warmup());
                    for (var point : points) catalog.get(definition.experiment()).validate(point.definition());
                    series.put(step.id(), points.size());
                }
            } catch (RuntimeException ex) {
                throw new ScenarioException(suite.id() + "/" + step.id() + ": " + ex.getMessage());
            }
            if (step.kind() == Kind.SERIES) series.put(step.id(), 0);
        }
        return suite;
    }

    private static void resolve(String ref, Map<String, Integer> series, String step) {
        int dot = ref.lastIndexOf(".p");
        if (series.containsKey(ref) && series.get(ref) == 0) return;
        if (dot > 0 && series.containsKey(ref.substring(0, dot))) {
            int position = Integer.parseInt(ref.substring(dot + 2));
            if (position < series.get(ref.substring(0, dot))) return;
        }
        throw new ScenarioException(step + ": compare needs an earlier series step or sweep point (<step>.p<N>): " + ref);
    }

    /** What would run, without a server: steps and the number of runs each submits. */
    private static void plan(List<Suite> suites, PrintStream out) {
        int total = 0;
        for (var suite : suites) {
            int runs = 0;
            var steps = new ArrayList<Map<String, Object>>();
            for (var step : suite.steps()) {
                int points = step.sweep().values().stream().mapToInt(List::size).reduce(1, (a, b) -> a * b);
                int count = switch (step.kind()) {
                    case RUN -> 1;
                    case SERIES -> step.repeat() + step.warmup();
                    case SWEEP -> points * (step.repeat() + step.warmup());
                    case COMPARE -> 0;
                };
                runs += count;
                steps.add(Map.of("step", step.id(), "kind", step.kind().name(), "runs", count));
            }
            total += runs;
            out.println(JSON.writeValueAsString(Map.of("suite", suite.id(), "title", suite.title(), "runs", runs, "steps", steps)));
        }
        out.println(JSON.writeValueAsString(Map.of("valid", true, "suites", suites.size(), "runs", total)));
    }

    // ---- running ------------------------------------------------------------------------------------------

    private static int run(List<Suite> suites, URI server, Integer timeoutOverride, Path report, PrintStream out, PrintStream err) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        var reports = new ArrayList<Map<String, Object>>();
        boolean allOk = true, aborted = false;
        long started = System.currentTimeMillis();
        try {
            for (var suite : suites) {
                var seriesIds = new HashMap<String, String>();
                var stepResults = new ArrayList<Map<String, Object>>();
                boolean suiteOk = true;
                for (var step : suite.steps()) {
                    Map<String, Object> result;
                    if (aborted) {
                        result = row(suite, step);
                        result.put("ok", false);
                        result.put("skipped", "an earlier step could not finish");
                    } else if (step.kind() == Kind.COMPARE) {
                        result = compare(client, server, suite, step, seriesIds);
                    } else {
                        result = execute(server, suite, step, timeoutOverride, seriesIds, err);
                        if (Boolean.TRUE.equals(result.get("abort"))) aborted = true;
                        result.remove("abort");
                    }
                    suiteOk &= Boolean.TRUE.equals(result.get("ok"));
                    out.println(JSON.writeValueAsString(result));
                    stepResults.add(result);
                }
                var summary = new LinkedHashMap<String, Object>();
                summary.put("suite", suite.id());
                summary.put("title", suite.title());
                summary.put("ok", suiteOk);
                summary.put("failedSteps", stepResults.stream().filter(r -> !Boolean.TRUE.equals(r.get("ok"))).map(r -> r.get("step")).toList());
                out.println(JSON.writeValueAsString(summary));
                var full = new LinkedHashMap<String, Object>(summary);
                full.put("file", suite.file().toString());
                full.put("situation", suite.situation());
                full.put("question", suite.question());
                full.put("steps", stepResults);
                reports.add(full);
                allOk &= suiteOk;
            }
        } finally {
            client.shutdownNow();
        }
        var total = new LinkedHashMap<String, Object>();
        total.put("ok", allOk);
        total.put("suites", reports.size());
        total.put("failedSuites", reports.stream().filter(r -> !Boolean.TRUE.equals(r.get("ok"))).map(r -> r.get("suite")).toList());
        total.put("elapsedSeconds", (System.currentTimeMillis() - started) / 1000);
        out.println(JSON.writeValueAsString(total));
        if (report != null) {
            var document = new LinkedHashMap<String, Object>(total);
            document.put("server", server.toString());
            document.put("startedAtEpochMs", started);
            document.put("results", reports);
            Files.writeString(report, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n", StandardCharsets.UTF_8);
        }
        return aborted ? 2 : allOk ? 0 : 1;
    }

    private static Map<String, Object> row(Suite suite, Step step) {
        var row = new LinkedHashMap<String, Object>();
        row.put("suite", suite.id());
        row.put("step", step.id());
        row.put("kind", step.kind().name());
        return row;
    }

    /** One run, series or sweep through the regular CLI path; the last JSON line it prints is the final state. */
    private static Map<String, Object> execute(URI server, Suite suite, Step step, Integer timeoutOverride,
            Map<String, String> seriesIds, PrintStream err) throws Exception {
        int timeout = timeoutOverride != null ? timeoutOverride : step.timeoutSeconds() != null ? step.timeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;
        var args = new ArrayList<>(List.of(step.kind() == Kind.SWEEP ? "sweep" : "run", step.scenario().toString(), "--server", server.toString()));
        if (step.kind() == Kind.SERIES || step.kind() == Kind.SWEEP) {
            args.addAll(List.of("--repeat", String.valueOf(step.repeat())));
            if (step.warmup() > 0) args.addAll(List.of("--warmup", String.valueOf(step.warmup())));
        }
        step.sweep().forEach((name, values) -> args.addAll(List.of("--grid",
                name + "=" + String.join(",", values.stream().map(String::valueOf).toList()))));
        args.addAll(List.of("--wait", "--timeout-seconds", String.valueOf(timeout)));
        var captured = new ByteArrayOutputStream();
        int code = LabCli.execute(args.toArray(String[]::new), new PrintStream(captured, true, StandardCharsets.UTF_8), err);
        var lines = captured.toString(StandardCharsets.UTF_8).lines().filter(l -> l.startsWith("{")).toList();
        var result = row(suite, step);
        result.put("expect", step.expect().name());
        // Exit 2 is a wait timeout and anything without a final state is an error: the server may still be busy,
        // so later steps would collide with it.
        if (code == 2 || lines.size() < 2) {
            result.put("ok", false);
            result.put("error", code == 2 ? "timed out after " + timeout + " s; it keeps running on the server" : "submission failed (see stderr)");
            result.put("abort", true);
            return result;
        }
        Map<?, ?> last = JSON.readValue(lines.getLast(), Map.class);
        String status;
        switch (step.kind()) {
            case RUN -> {
                status = String.valueOf(last.get("status"));
                result.put("id", last.get("runId"));
                result.put("dashboard", "/app/runs/" + last.get("runId"));
                Map<?, ?> summary = (Map<?, ?>) last.get("summary");
                var failures = new ArrayList<String>();
                step.asserts().forEach((counter, expected) -> {
                    Object actual = ((Map<?, ?>) summary.get("counters")).get(counter);
                    if (!(actual instanceof Number n) || n.longValue() != expected) failures.add(counter + " expected " + expected + " but was " + actual);
                });
                if (!step.asserts().isEmpty()) result.put("assertions", failures.isEmpty() ? "passed" : failures);
                result.put("failureMessage", last.get("failureMessage"));
                result.put("ok", matches(step.expect(), status) && failures.isEmpty());
            }
            case SERIES -> {
                Map<?, ?> series = (Map<?, ?>) last.get("series");
                status = String.valueOf(series.get("status"));
                String id = String.valueOf(series.get("seriesId"));
                seriesIds.put(step.id(), id);
                result.put("id", id);
                result.put("dashboard", "/app/series/" + id);
                result.put("measures", measures(last));
                result.put("cautions", cautions(last));
                result.put("ok", matches(step.expect(), status));
            }
            default -> {
                Map<?, ?> sweep = (Map<?, ?>) last.get("sweep");
                status = String.valueOf(sweep.get("status"));
                result.put("id", sweep.get("sweepId"));
                result.put("dashboard", "/app/sweeps/" + sweep.get("sweepId"));
                var points = new ArrayList<Map<String, Object>>();
                for (Object raw : (List<?>) last.get("points")) {
                    Map<?, ?> point = (Map<?, ?>) raw;
                    var row = new LinkedHashMap<String, Object>();
                    row.put("point", step.id() + ".p" + point.get("position"));
                    row.put("parameters", point.get("parameters"));
                    if (point.get("series") instanceof Map<?, ?> view) {
                        String id = String.valueOf(((Map<?, ?>) view.get("series")).get("seriesId"));
                        seriesIds.put(step.id() + ".p" + point.get("position"), id);
                        row.put("status", ((Map<?, ?>) view.get("series")).get("status"));
                        row.put("measures", measures(view));
                        var cautions = cautions(view);
                        if (!cautions.isEmpty()) row.put("cautions", cautions);
                    }
                    points.add(row);
                }
                result.put("points", points);
                // A sweep that may fail is looking for a boundary: at least one point still has to succeed,
                // otherwise every point failed for the same unrelated reason.
                boolean anySucceeded = points.stream().anyMatch(p -> "SUCCEEDED".equals(p.get("status")));
                result.put("ok", matches(step.expect(), status) && (step.expect() != Expect.ANY || anySucceeded));
            }
        }
        result.put("status", status);
        return result;
    }

    private static boolean matches(Expect expect, String status) {
        return switch (expect) {
            case ANY -> Set.of("SUCCEEDED", "FAILED").contains(status);
            case SUCCEEDED -> status.equals("SUCCEEDED");
            case FAILED -> status.equals("FAILED");
        };
    }

    private static List<Map<String, Object>> measures(Map<?, ?> view) {
        var rows = new ArrayList<Map<String, Object>>();
        for (Object raw : (List<?>) view.get("measures")) {
            if (raw == null) continue;
            Map<?, ?> m = (Map<?, ?>) raw;
            Map<?, ?> stats = (Map<?, ?>) m.get("statistics");
            var row = new LinkedHashMap<String, Object>();
            row.put("role", m.get("role"));
            row.put("unit", m.get("unit"));
            row.put("n", stats.get("count"));
            row.put("median", stats.get("median"));
            row.put("min", stats.get("min"));
            row.put("max", stats.get("max"));
            row.put("cv", stats.get("coefficientOfVariation"));
            rows.add(row);
        }
        return rows;
    }

    private static List<String> cautions(Map<?, ?> view) {
        var cautions = new ArrayList<String>();
        if (view.get("overlappingRunIds") instanceof List<?> overlap && !overlap.isEmpty())
            cautions.add(overlap.size() + " other runs overlapped the repetitions");
        if (view.get("stability") instanceof Map<?, ?> stability && stability.get("highVariationRoles") instanceof List<?> noisy && !noisy.isEmpty())
            cautions.add("coefficient of variation above 10%: " + noisy);
        if (view.get("checks") instanceof List<?> checks)
            for (Object raw : checks) {
                Map<?, ?> c = (Map<?, ?>) raw;
                if (((Number) c.get("passed")).intValue() > 0 && ((Number) c.get("failed")).intValue() > 0)
                    cautions.add("check " + c.get("checkId") + " both passed and failed");
            }
        return cautions;
    }

    private static Map<String, Object> compare(HttpClient client, URI server, Suite suite, Step step, Map<String, String> seriesIds) throws Exception {
        var result = row(suite, step);
        String baseline = seriesIds.get(step.baseline()), candidate = seriesIds.get(step.candidate());
        result.put("baseline", step.baseline());
        result.put("candidate", step.candidate());
        if (baseline == null || candidate == null) {
            result.put("ok", false);
            result.put("error", "a compared series did not run");
            return result;
        }
        String query = "baseline=" + URLEncoder.encode(baseline, StandardCharsets.UTF_8) + "&candidate=" + URLEncoder.encode(candidate, StandardCharsets.UTF_8);
        Map<?, ?> comparison = LabCli.object(LabCli.request(client, server.resolve("/run-series/compare?" + query), null, null, Duration.ofSeconds(30)));
        result.put("dashboard", "/app/series/compare?" + query);
        result.put("sameDefinition", comparison.get("sameDefinition"));
        var rows = new ArrayList<Map<String, Object>>();
        for (Object raw : (List<?>) comparison.get("measures")) {
            Map<?, ?> m = (Map<?, ?>) raw;
            var row = new LinkedHashMap<String, Object>();
            row.put("role", m.get("role"));
            row.put("unit", m.get("unit"));
            row.put("baselineMedian", ((Map<?, ?>) m.get("baseline")).get("median"));
            row.put("candidateMedian", ((Map<?, ?>) m.get("candidate")).get("median"));
            row.put("medianChangePct", m.get("medianChangePct"));
            row.put("separation", m.get("separation"));
            rows.add(row);
        }
        result.put("measures", rows);
        // A comparison reports; it never fails a suite on its outcome, since no direction of change is "expected".
        result.put("ok", true);
        return result;
    }

    // ---- small parsing helpers ----------------------------------------------------------------------------

    private static Map<?, ?> map(Object value, String name, Set<String> allowed) {
        if (!(value instanceof Map<?, ?> map)) throw new ScenarioException(name + " must be a mapping");
        if (allowed != null)
            for (Object key : map.keySet())
                if (!allowed.contains(String.valueOf(key))) throw new ScenarioException("Unknown key in " + name + ": " + key);
        return map;
    }

    private static String id(Object value, String name) {
        if (!(value instanceof String s) || !s.matches(ID)) throw new ScenarioException(name + " must match " + ID);
        return s;
    }

    private static String reference(Object value, String step) {
        if (!(value instanceof String s) || s.isBlank()) throw new ScenarioException(step + ": compare needs baseline and candidate");
        return s;
    }

    private static String text(Object value, String name, int max) {
        if (!(value instanceof String s) || s.isBlank() || s.length() > max) throw new ScenarioException(name + " must be 1.." + max + " characters");
        return s.strip();
    }

    private static int integer(Object value, String name, int min, int max) {
        if (!(value instanceof Integer i) || i < min || i > max) throw new ScenarioException(name + " must be an integer " + min + ".." + max);
        return i;
    }
}
