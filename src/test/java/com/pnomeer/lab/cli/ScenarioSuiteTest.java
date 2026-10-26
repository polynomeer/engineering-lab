package com.pnomeer.lab.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioSuiteTest {
    @TempDir Path root;
    private static final String QUEUE = Path.of("experiments/concurrency/queue-contention/scenarios/small-buffer.yaml").toAbsolutePath().toString();
    private final JsonMapper json = JsonMapper.builder().build();

    private record Result(int code, String out, String err) { }
    private static Result suite(String... args) {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int code = LabCli.execute(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
    private Path file(String steps) throws IOException {
        Path file = root.resolve(UUID.randomUUID() + ".suite.yaml");
        Files.writeString(file, """
                schemaVersion: 1
                suite: {id: s, title: t, situation: a situation, question: a question}
                steps:
                """ + steps);
        return file;
    }

    @Test void everyCheckedInSuiteValidatesAndPlansItsRuns() throws Exception {
        try (var files = Files.list(Path.of("experiments/suites"))) {
            var args = new ArrayList<>(List.of("suite"));
            files.filter(p -> p.toString().endsWith(".suite.yaml")).sorted().forEach(p -> args.add(p.toString()));
            assertTrue(args.size() >= 7, "six or more suites");
            args.add("--dry-run");
            var result = suite(args.toArray(String[]::new));
            assertEquals(0, result.code, result.err);
            Map<?, ?> total = json.readValue(result.out.lines().toList().getLast(), Map.class);
            assertEquals(true, total.get("valid"));
            assertEquals(args.size() - 2, total.get("suites"));
        }
    }

    @Test void plansRunCountsPerStepKind() throws Exception {
        var result = suite("suite", file("""
                  - {id: one, scenario: %1$s}
                  - {id: series, scenario: %1$s, repeat: 3, warmup: 1}
                  - {id: sweep, scenario: %1$s, sweep: {queueCapacity: [1, 4], consumerThreads: [1, 2]}, repeat: 2}
                  - {id: cmp, compare: {baseline: series, candidate: sweep.p3}}
                """.formatted(QUEUE)).toString(), "--dry-run");
        assertEquals(0, result.code, result.err);
        Map<?, ?> plan = json.readValue(result.out.lines().toList().getFirst(), Map.class);
        assertEquals(1 + 4 + 8, plan.get("runs"));
    }

    /** Validation happens before any submission, so these never reach the (absent) server. */
    @Test void rejectsMistakesBeforeRunningAnything() throws Exception {
        String unused = "http://127.0.0.1:9";
        for (String steps : List.of(
                "  - {id: a, scenario: %s, unknown: 1}\n",
                "  - {id: a, scenario: %1$s}\n  - {id: a, scenario: %1$s}\n",
                "  - {id: a, scenario: missing.yaml}\n",
                "  - {id: a, scenario: %s, sweep: {queueCapacity: [1, 0]}}\n",
                "  - {id: a, scenario: %s, repeat: 3, assert: {items.produced: 1}}\n",
                "  - {id: a, scenario: %s, warmup: 1}\n",
                "  - {id: a, scenario: %s}\n  - {id: c, compare: {baseline: a, candidate: a}}\n",
                "  - {id: c, compare: {baseline: b, candidate: b}}\n  - {id: b, scenario: %s, repeat: 3}\n",
                "  - {id: a, scenario: %s, sweep: {queueCapacity: [1, 2]}}\n  - {id: c, compare: {baseline: a.p0, candidate: a.p2}}\n",
                "  - {id: a, scenario: %s, expect: MAYBE}\n")) {
            var result = suite("suite", file(steps.formatted(QUEUE)).toString(), "--server", unused);
            assertEquals(1, result.code, steps);
            assertTrue(result.out.isEmpty(), "nothing ran for: " + steps);
        }
        assertEquals(1, suite("suite").code);
        assertEquals(1, suite("suite", file("  - {id: a, scenario: %s}\n".formatted(QUEUE)).toString(), "--bogus").code);
    }
}
