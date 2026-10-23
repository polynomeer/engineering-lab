package com.pnomeer.lab.cli;

import com.pnomeer.lab.EngineeringLabApplication;
import com.pnomeer.lab.core.storage.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=EngineeringLabApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"lab.storage.root=${java.io.tmpdir}/lab-cli-tests-${random.uuid}", "lab.observation.jvm-enabled=true", "lab.observation.jvm-interval-ms=100", "lab.observation.max-periodic-samples=2", "lab.trace.enabled=true", "lab.trace.max-spans=7", "lab.jfr.enabled=true", "lab.jfr.duration-seconds=60", "lab.jfr.max-bytes=1048576", "lab.logs.enabled=true"})
class LabCliIntegrationTest {
    @LocalServerPort int port;
    @Autowired RunStore store;
    @Autowired com.pnomeer.lab.app.ingest.IngestionService ingestion;
    private final JsonMapper json = JsonMapper.builder().build();
    @Test void validatesAndRunsAllCheckedInExamplesThroughTheActualHttpServer() throws Exception {
        for (String file : List.of("experiments/concurrency/queue-contention/scenarios/small-buffer.yaml",
                "experiments/io/endpoint-comparison/scenarios/baseline.json",
                "experiments/pipeline/excel-ingestion/scenarios/small-batch.yaml")) {
            var validation = run("validate", file);
            assertEquals(0, validation.code, validation.err);
            String hash = (String)json.readValue(validation.out.strip(), Map.class).get("definitionSha256");
            var execution = run("run", file, "--server", "http://localhost:"+port, "--wait", "--timeout-seconds", "10");
            assertEquals(0, execution.code, execution.err);
            var lines = execution.out.lines().toList();
            assertEquals(2, lines.size());
            var submitted = json.readValue(lines.getFirst(), Map.class);
            String id = (String)submitted.get("runId");
            var result = store.find(id).orElseThrow();
            assertEquals(RunStatus.SUCCEEDED, result.status());
            assertEquals("BASIC_V1", result.runMetadata().parameters().get("observation.jfr.mode"));
            assertEquals(2, store.artifacts(id).size());
            for (var reference : store.artifacts(id)) verifyDownload(reference);
            assertEquals("LIFECYCLE_V1", result.runMetadata().parameters().get("observation.logs.mode"));
            assertEquals(3L, result.summary().counters().get("observation.logs.events"));
            assertTrue(store.artifacts(id).stream().anyMatch(ref -> ref.mediaType().equals("application/x-ndjson")));
            assertTrue(result.summary().counters().get("observation.jfr.bytes") <= 1048576);
            assertEquals("EXPLICIT", result.runMetadata().parameters().get("observation.trace.mode"));
            assertTrue(result.summary().counters().get("trace.spans.recorded") >= 1);
            assertTrue(result.summary().counters().get("trace.spans.recorded") <= 7);
            if (result.experimentType().equals("io.endpoint-comparison")) {
                var spans = store.recentMetrics(id, 100).stream().map(StoredMetric::point)
                        .filter(point -> "trace.span".equals(point.labels().get("source"))).toList();
                assertEquals(7, spans.size());
                assertTrue(spans.stream().anyMatch(point -> "IO_VIRTUAL_REQUEST".equals(point.labels().get("stage"))
                        && "1".equals(point.labels().get("parentSpanId"))));
                assertTrue(spans.stream().anyMatch(point -> "IO_REACTIVE_REQUEST".equals(point.labels().get("stage"))));
                assertTrue(result.summary().counters().get("trace.spans.dropped") > 0);
            }
            assertEquals("PERIODIC", result.runMetadata().parameters().get("observation.jvm.mode"));
            assertTrue(result.summary().counters().get("observation.jvm.samples") >= 2L);
            assertTrue(result.summary().counters().get("observation.jvm.samples") <= 4L);
            if (result.experimentType().equals("pipeline.excel-ingestion")) {
                assertEquals("1000", result.runMetadata().parameters().get("pipeline.insert.flush-interval-ms"));
                assertEquals("3", result.runMetadata().parameters().get("pipeline.insert.max-attempts"));
                assertEquals("100", result.runMetadata().parameters().get("pipeline.error-preview-limit"));
                assertEquals("30000", result.runMetadata().parameters().get("pipeline.shutdown-timeout-ms"));
                assertEquals("100", result.runMetadata().parameters().get("pipeline.insert.retry-backoff-ms"));
                assertEquals(0L, result.summary().counters().get("retry.count"));
                assertEquals(5, ingestion.getJob(id).getInsertedCount());
                assertTrue(ingestion.getJob(id).getMetricTimeline().stream()
                        .noneMatch(point -> Set.of("jvm.boundary", "jvm.periodic", "trace.span").contains(point.labels().getOrDefault("source", ""))));
            }
            assertEquals(hash, result.runMetadata().parameters().get("scenario.definition.sha256"));
            assertEquals(hash, submitted.get("definitionSha256"));
        }
        var catalog = run("list", "--server", "http://localhost:"+port);
        assertEquals(0, catalog.code, catalog.err);
        assertEquals(3, json.readValue(catalog.out.strip(), List.class).size());
    }
    @Test void repeatsAFileInputScenarioPinsABaselineAndChecksAgainstIt() throws Exception {
        String file = "experiments/pipeline/excel-ingestion/scenarios/small-batch.yaml", server = "http://localhost:" + port;
        var first = run("run", file, "--server", server, "--repeat", "3", "--warmup", "1", "--wait", "--timeout-seconds", "120", "--check-baseline");
        assertEquals(LabCli.EXIT_INCONCLUSIVE, first.code, first.err); // no baseline pinned yet
        var lines = first.out.lines().toList();
        assertEquals(3, lines.size()); // submission, finished series, regression check
        String seriesId = (String)json.readValue(lines.getFirst(), Map.class).get("seriesId");
        assertEquals("NO_BASELINE", json.readValue(lines.get(2), Map.class).get("overall"));
        Map<?, ?> view = json.readValue(lines.get(1), Map.class);
        assertEquals(3, view.get("measuredSucceeded"));
        // Each repetition consumed its own staged copy of the one uploaded workbook.
        for (Object member : (List<?>)view.get("members")) {
            var run = store.find((String)((Map<?, ?>)member).get("runId")).orElseThrow();
            assertEquals(RunStatus.SUCCEEDED, run.status());
            assertEquals(5, ingestion.getJob(run.runId()).getInsertedCount());
        }
        var pinned = run("baseline", seriesId, "--server", server);
        assertEquals(0, pinned.code, pinned.err);
        // A generous tolerance keeps timing noise from failing the check; the verdict logic has unit tests.
        var second = run("run", file, "--server", server, "--repeat", "3", "--wait", "--timeout-seconds", "120",
                "--check-baseline", "--tolerance-pct", "100");
        assertEquals(0, second.code, second.err + second.out);
        var check = json.readValue(second.out.lines().toList().get(2), Map.class);
        assertEquals("PASS", check.get("overall"));
        assertEquals(seriesId, check.get("baselineSeriesId"));

        // The same baseline carried as a file, as CI does with a fresh store.
        var exported = java.nio.file.Files.createTempFile("lab-baseline-", ".json");
        var export = run("baseline", seriesId, "--export", exported.toString(), "--server", server);
        assertEquals(0, export.code, export.err);
        Map<?, ?> baselineFile = json.readValue(java.nio.file.Files.readString(exported), Map.class);
        assertEquals(seriesId, baselineFile.get("sourceSeriesId"));
        var fromFile = run("run", file, "--server", server, "--repeat", "3", "--wait", "--timeout-seconds", "120",
                "--check-baseline-file", exported.toString(), "--tolerance-pct", "100");
        assertEquals(0, fromFile.code, fromFile.err + fromFile.out);
        var fileCheck = json.readValue(fromFile.out.lines().toList().get(2), Map.class);
        assertEquals("PASS", fileCheck.get("overall"));
        assertEquals("file:" + seriesId, fileCheck.get("baselineSeriesId"));
        assertEquals(List.of(), fileCheck.get("cautions")); // same machine, same definition
        assertEquals(1, run("run", file, "--repeat", "3", "--wait", "--check-baseline", "--check-baseline-file", exported.toString()).code);
        assertEquals(1, run("run", file, "--repeat", "3", "--wait", "--check-baseline-file", "missing.json").code);
        java.nio.file.Files.deleteIfExists(exported);
        assertEquals(1, run("run", file, "--warmup", "1").code); // --warmup needs --repeat
        assertEquals(1, run("run", file, "--repeat", "3", "--check-baseline").code); // the check needs --wait
    }
    @Test void sweepsAGridAndWaitsForEveryPoint() throws Exception {
        var sweep = run("sweep", "experiments/concurrency/queue-contention/scenarios/small-buffer.yaml", "--server", "http://localhost:" + port,
                "--grid", "queueCapacity=1,4", "--repeat", "3", "--wait", "--timeout-seconds", "120");
        assertEquals(0, sweep.code, sweep.err);
        var lines = sweep.out.lines().toList();
        assertEquals(2, lines.size());
        Map<?, ?> view = json.readValue(lines.get(1), Map.class);
        var points = (List<?>)view.get("points");
        assertEquals(2, points.size());
        assertEquals(4, ((Map<?, ?>)((Map<?, ?>)points.get(1)).get("parameters")).get("queueCapacity"));
    }
    @Test void previewsRetentionAndRefusesToApplyWhileDisabled() throws Exception {
        var preview = run("retention", "--older-than-days", "30", "--server", "http://localhost:" + port);
        assertEquals(0, preview.code, preview.err);
        Map<?, ?> listed = json.readValue(preview.out.strip(), Map.class);
        assertEquals(false, listed.get("applyEnabled"));
        assertEquals(List.of(), listed.get("candidates")); // every capture here is younger than 30 days
        var apply = run("retention", "--older-than-days", "30", "--apply", "--server", "http://localhost:" + port);
        assertEquals(1, apply.code);
        assertTrue(apply.err.contains("403"), apply.err);
        assertEquals(1, run("retention", "--server", "http://localhost:" + port).code); // days are required
    }
    private void verifyDownload(ArtifactReference reference) throws Exception {
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
                    + "/runs/" + reference.runId() + "/artifacts/" + reference.artifactId() + "/download"))
                    .timeout(java.time.Duration.ofSeconds(10)).GET().build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertEquals(reference.byteSize(), response.body().length);
            assertEquals(reference.sha256(), java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(response.body())));
        }
    }
    private Result run(String... args) {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int code = LabCli.execute(args, new PrintStream(out), new PrintStream(err));
        return new Result(code, out.toString(java.nio.charset.StandardCharsets.UTF_8), err.toString(java.nio.charset.StandardCharsets.UTF_8));
    }
    private record Result(int code, String out, String err) { }
}
