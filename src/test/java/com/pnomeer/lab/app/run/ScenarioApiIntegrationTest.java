package com.pnomeer.lab.app.run;

import com.pnomeer.pipeline.ExcelPipelineApplication;
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

@SpringBootTest(classes=ExcelPipelineApplication.class, properties="lab.storage.root=${java.io.tmpdir}/lab-scenario-api-${random.uuid}")
class ScenarioApiIntegrationTest {
    @Autowired ScenarioRunController controller;
    @Autowired ExperimentCatalogController catalog;
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
        mvc = MockMvcBuilders.standaloneSetup(controller, catalog).setControllerAdvice(new ScenarioExceptionHandler()).build();
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
    @Test void bindsExcelSettingsPerRunAndValidatesUploadedHashBeforeCreation() throws Exception {
        byte[] bytes;
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(); sheet.createRow(0).createCell(0).setCellValue("header");
            var row = sheet.createRow(1); row.createCell(0).setCellValue("a"); row.createCell(1).setCellValue("b"); row.createCell(2).setCellValue("1");
            workbook.write(out); bytes = out.toByteArray();
        }
        String definition = """
                schemaVersion: 1
                experiment: pipeline.excel-ingestion
                scenario: {id: excel-small-batch, hypothesis: Small batches preserve valid rows}
                parameters: {mode: SINGLE_THREAD, rawCapacity: 2, mappedCapacity: 3, validatorThreads: 1, inserterThreads: 1, chunkSize: 1, offerTimeoutMs: 50, flushIntervalMs: 25, maxAttempts: 2, retryBackoffMs: 7}
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
        assertEquals(1, ingestion.getJob(id).getInsertedCount());
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
