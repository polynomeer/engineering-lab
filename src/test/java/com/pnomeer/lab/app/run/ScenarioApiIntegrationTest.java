package com.pnomeer.lab.app.run;

import com.pnomeer.lab.EngineeringLabApplication;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.scenario.*;
import com.pnomeer.lab.app.ingest.IngestionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=EngineeringLabApplication.class, properties="lab.storage.root=${java.io.tmpdir}/lab-scenario-api-${random.uuid}")
class ScenarioApiIntegrationTest {
    @Autowired ScenarioRunController controller;
    @Autowired ExperimentCatalogController catalog;
    @Autowired ModuleCatalogController modules;
    @Autowired PatternCatalogController patterns;
    @Autowired StudyCatalogController studies;
    @Autowired MeasurementController measurements;
    @Autowired ExecutionEvidenceController executionEvidence;
    @Autowired RunStore store;
    @Autowired IngestionService ingestion;
    private MockMvc mvc;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String QUEUE = """
            {"schemaVersion":1,"experiment":"concurrency.queue-contention",
            "scenario":{"id":"api-definition","hypothesis":"A full queue makes producers wait"},
            "parameters":{"queueCapacity":2,"producerThreads":1,"consumerThreads":1,"itemsPerProducer":10,"consumerDelayMs":1},
            "measurement":{"mode":"SINGLE_PASS"}}
            """;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller, catalog, modules, executionEvidence, patterns, studies, measurements).setControllerAdvice(new ScenarioExceptionHandler()).build();
    }
    @Test void exposesModuleOwnershipWithoutChangingExperimentIds() throws Exception {
        mvc.perform(get("/modules")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].module.id").value("concurrency"))
                .andExpect(jsonPath("$[0].module.collection").value("engineering"))
                .andExpect(jsonPath("$[0].experiments[0].id").value("concurrency.queue-contention"));
    }
    @Test void listsSchemasAndPersistsRepeatableDefinitions() throws Exception {
        mvc.perform(get("/experiments")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/experiments/concurrency.queue-contention")).andExpect(status().isOk())
                .andExpect(jsonPath("$.parameters.queueCapacity.minimum").value(1));
        mvc.perform(get("/experiments/unknown")).andExpect(status().isNotFound());
        String first = submit(QUEUE), second = submit(QUEUE);
        assertNotEquals(first, second);
        var a = completed(first); var b = completed(second);
        assertEquals(RunStatus.SUCCEEDED, a.status());
        assertEquals(10L, a.summary().counters().get("items.consumed"));
        assertEquals(a.runMetadata().parameters().get("scenario.definition.sha256"), b.runMetadata().parameters().get("scenario.definition.sha256"));
        assertTrue(a.runMetadata().parameters().get("scenario.definition").contains("api-definition"));
        assertEquals("SINGLE_PASS", a.runMetadata().parameters().get("measurement.mode"));
        var contractKey = com.pnomeer.lab.registry.measurement.MeasurementContract.PARAMETER;
        assertTrue(com.pnomeer.lab.registry.measurement.MeasurementContract.read(a.runMetadata().parameters().get(contractKey), a.experimentType()).isPresent());
        assertEquals(a.runMetadata().parameters().get(contractKey), b.runMetadata().parameters().get(contractKey));
        mvc.perform(get("/dashboard/v1/measurement-catalog")).andExpect(status().isOk()).andExpect(jsonPath("$.contracts.length()").value(3));
        mvc.perform(get("/dashboard/v1/comparison-assessment").param("baseline", first).param("candidate", second))
                .andExpect(status().isOk()).andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.baselineContract.experimentId").value("concurrency.queue-contention"))
                .andExpect(jsonPath("$.measures[0].baselineValue").isNumber());
        mvc.perform(get("/dashboard/v1/comparison-assessment").param("baseline", "missing").param("candidate", second)).andExpect(status().isNotFound());
        assertEquals(a,store.find(first).orElseThrow());
        mvc.perform(get("/dashboard/v1/runs/" + first + "/execution")).andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.definition.moduleId").value("concurrency"))
                .andExpect(jsonPath("$.execution.definition.kind").value("IN_PROCESS"))
                .andExpect(jsonPath("$.execution.phase").value("FINALIZING"))
                .andExpect(jsonPath("$.execution.evidenceComplete").value(true));
    }
    @Test void patternDiscoveryPreservesLegacyCatalogsAndStoredScenarioIdentity() throws Exception {
        String experimentsBefore = mvc.perform(get("/experiments")).andReturn().getResponse().getContentAsString();
        String modulesBefore = mvc.perform(get("/modules")).andReturn().getResponse().getContentAsString();
        var before = completed(submit(QUEUE));
        mvc.perform(get("/dashboard/v1/pattern-catalog")).andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.experiments.length()").value(3))
                .andExpect(jsonPath("$.experiments[0].experimentId").value("concurrency.queue-contention"));
        mvc.perform(get("/dashboard/v1/study-catalog")).andExpect(status().isOk()).andExpect(jsonPath("$.studies.length()").value(3));
        assertEquals(before, store.find(before.runId()).orElseThrow());
        var repeated = completed(submit(QUEUE));
        assertEquals(before.runMetadata().parameters().get("scenario.definition.sha256"),
                repeated.runMetadata().parameters().get("scenario.definition.sha256"));
        assertEquals(experimentsBefore, mvc.perform(get("/experiments")).andReturn().getResponse().getContentAsString());
        assertEquals(modulesBefore, mvc.perform(get("/modules")).andReturn().getResponse().getContentAsString());
    }
    @Test void invalidDefinitionsDoNotCreateRuns() throws Exception {
        int before = store.list(null, Long.MAX_VALUE, 500).size();
        for (String invalid : List.of(QUEUE.replace("queueCapacity\":2", "queueCapacity\":\"2\""),
                QUEUE.replace("SINGLE_PASS", "WARMUP"), QUEUE.replace("concurrency.queue-contention", "unknown"),
                QUEUE.replace("\"queueCapacity\":2", "\"extra\":1,\"queueCapacity\":2"))) {
            mvc.perform(post("/runs").contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/runs").contentType("application/json").content(" ".repeat(65537))).andExpect(status().isBadRequest());
        assertEquals(before, store.list(null, Long.MAX_VALUE, 500).size());
    }
    @Test void runsExcelInAForkedJvmWithItsOwnHeapLimitAndKeepsDeadLetters() throws Exception {
        byte[] bytes;
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(); sheet.createRow(0).createCell(0).setCellValue("header");
            var row = sheet.createRow(1); row.createCell(0).setCellValue("a"); row.createCell(1).setCellValue("b"); row.createCell(2).setCellValue("1");
            var invalid = sheet.createRow(2); invalid.createCell(0).setCellValue("a"); invalid.createCell(1).setCellValue(""); invalid.createCell(2).setCellValue("2");
            workbook.write(out); bytes = out.toByteArray();
        }
        String definition = """
                schemaVersion: 1
                experiment: pipeline.excel-ingestion
                scenario: {id: excel-forked, hypothesis: A forked JVM reports its own heap}
                parameters: {mode: PIPELINE, rawCapacity: 4, mappedCapacity: 4, validatorThreads: 1, inserterThreads: 1, chunkSize: 2, offerTimeoutMs: 50, forkedHeapMiB: 96}
                measurement: {mode: SINGLE_PASS}
                input: {file: sample.xlsx, sha256: '%s'}
                """.formatted(ScenarioDocuments.sha256(bytes));
        var file = new MockMultipartFile("file", "sample.xlsx", "application/octet-stream", bytes);
        var document = new MockMultipartFile("scenario", "scenario.yaml", "application/yaml", definition.getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(multipart("/runs").file(document).file(file)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        var run = completed((String)json.readValue(body, Map.class).get("runId"));
        assertEquals(RunStatus.SUCCEEDED, run.status(), run.failureMessage());
        assertEquals("FORKED_JVM", run.runMetadata().parameters().get("execution.isolation"));
        assertEquals("96", run.runMetadata().parameters().get("execution.forked.heapMiB"));
        assertEquals(1L, run.summary().counters().get("rows.inserted"));
        long max = run.summary().counters().get("heap.maxBytes");
        assertTrue(max > 0 && max <= 96L * 1024 * 1024, "child heap limit, not the server's: " + max);
        assertTrue(run.summary().counters().get("heap.sampledPeakBytes") > 0);
        assertEquals(1, store.deadLetters(run.runId(), -1, 10).size());

        String tooSmall = definition.replace("forkedHeapMiB: 96", "forkedHeapMiB: 16");
        var rejected = new MockMultipartFile("scenario", "scenario.yaml", "application/yaml", tooSmall.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/runs").file(rejected).file(file)).andExpect(status().isBadRequest());
    }

    @Test void bindsExcelSettingsPerRunAndValidatesUploadedHashBeforeCreation() throws Exception {
        byte[] bytes;
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(); sheet.createRow(0).createCell(0).setCellValue("header");
            var row = sheet.createRow(1); row.createCell(0).setCellValue("a"); row.createCell(1).setCellValue("b"); row.createCell(2).setCellValue("1");
            var invalid = sheet.createRow(2); invalid.createCell(0).setCellValue("a"); invalid.createCell(1).setCellValue(""); invalid.createCell(2).setCellValue("2");
            workbook.write(out); bytes = out.toByteArray();
        }
        String definition = """
                schemaVersion: 1
                experiment: pipeline.excel-ingestion
                scenario: {id: excel-small-batch, hypothesis: Small batches preserve valid rows}
                parameters: {mode: SINGLE_THREAD, rawCapacity: 2, mappedCapacity: 3, validatorThreads: 1, inserterThreads: 1, chunkSize: 1, offerTimeoutMs: 50, flushIntervalMs: 25, maxAttempts: 2, retryBackoffMs: 7, errorPreviewLimit: 0, shutdownTimeoutMs: 1000}
                measurement: {mode: SINGLE_PASS}
                input: {file: sample.xlsx, sha256: '%s'}
                """.formatted(ScenarioDocuments.sha256(bytes));
        var file = new MockMultipartFile("file", "ignored-name.xlsx", "application/octet-stream", bytes);
        var document = new MockMultipartFile("scenario", "scenario.yaml", "application/yaml", definition.getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(multipart("/runs").file(document).file(file)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id = (String)json.readValue(body, Map.class).get("runId");
        var run = completed(id);
        assertEquals(RunStatus.SUCCEEDED, run.status());
        assertEquals("excel-small-batch", run.scenarioId());
        assertEquals("1", run.runMetadata().parameters().get("pipeline.insert.chunk-size"));
        assertEquals("25", run.runMetadata().parameters().get("pipeline.insert.flush-interval-ms"));
        assertEquals("2", run.runMetadata().parameters().get("pipeline.insert.max-attempts"));
        assertEquals("7", run.runMetadata().parameters().get("pipeline.insert.retry-backoff-ms"));
        assertEquals("0", run.runMetadata().parameters().get("pipeline.error-preview-limit"));
        assertEquals("1000", run.runMetadata().parameters().get("pipeline.shutdown-timeout-ms"));
        assertEquals(1L, run.summary().counters().get("rows.validationErrors"));
        assertEquals(1, ingestion.getJob(id).getInsertedCount());
        assertEquals(1, store.deadLetters(id, -1, 100).size());
        assertEquals("col2 is required", store.deadLetters(id, -1, 100).getFirst().reason());
        assertEquals(com.pnomeer.lab.experiments.pipeline.PipelineRunner.RunMode.SINGLE_THREAD, ingestion.getJob(id).getRunMode());
        int before = store.list(null, Long.MAX_VALUE, 500).size();
        mvc.perform(multipart("/runs").file(document).file(new MockMultipartFile("file", new byte[]{1})))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/runs").contentType("application/yaml").content(definition)).andExpect(status().isBadRequest());
        assertEquals(before, store.list(null, Long.MAX_VALUE, 500).size());
    }
    private String submit(String text) throws Exception {
        String body = mvc.perform(post("/runs").contentType("application/json").content(text)).andExpect(status().isAccepted())
                .andExpect(header().exists("Location")).andReturn().getResponse().getContentAsString();
        return (String)json.readValue(body, Map.class).get("runId");
    }
    private StoredRun completed(String id) {
        return assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            while (true) {
                var run = store.find(id).orElseThrow();
                if (run.status() != RunStatus.QUEUED && run.status() != RunStatus.RUNNING) return run;
                Thread.sleep(20);
            }
        });
    }
}
