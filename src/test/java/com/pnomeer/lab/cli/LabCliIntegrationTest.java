package com.pnomeer.lab.cli;

import com.pnomeer.pipeline.ExcelPipelineApplication;
import com.pnomeer.lab.core.storage.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=ExcelPipelineApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"lab.storage.root=${java.io.tmpdir}/lab-cli-tests-${random.uuid}", "lab.observation.jvm-enabled=true", "lab.observation.jvm-interval-ms=100", "lab.observation.max-periodic-samples=2"})
class LabCliIntegrationTest {
    @LocalServerPort int port;
    @Autowired RunStore store;
    @Autowired com.pnomeer.lab.app.ingest.IngestionService ingestion;
    private final JsonMapper json = JsonMapper.builder().build();
    @Test void validatesAndRunsAllCheckedInExamplesThroughTheActualHttpServer() {
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
            assertEquals("PERIODIC", result.runMetadata().parameters().get("observation.jvm.mode"));
            assertTrue(result.summary().counters().get("observation.jvm.samples") >= 2L);
            assertTrue(result.summary().counters().get("observation.jvm.samples") <= 4L);
            if (result.experimentType().equals("pipeline.excel-ingestion")) {
                assertEquals(5, ingestion.getJob(id).getInsertedCount());
                assertTrue(ingestion.getJob(id).getMetricTimeline().stream()
                        .noneMatch(point -> Set.of("jvm.boundary", "jvm.periodic").contains(point.labels().getOrDefault("source", ""))));
            }
            assertEquals(hash, result.runMetadata().parameters().get("scenario.definition.sha256"));
            assertEquals(hash, submitted.get("definitionSha256"));
        }
        var catalog = run("list", "--server", "http://localhost:"+port);
        assertEquals(0, catalog.code, catalog.err);
        assertEquals(3, json.readValue(catalog.out.strip(), List.class).size());
    }
    private Result run(String... args) {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int code = LabCli.execute(args, new PrintStream(out), new PrintStream(err));
        return new Result(code, out.toString(java.nio.charset.StandardCharsets.UTF_8), err.toString(java.nio.charset.StandardCharsets.UTF_8));
    }
    private record Result(int code, String out, String err) { }
}
