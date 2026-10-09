package com.pnomeer.lab.app.run;

import com.pnomeer.pipeline.ExcelPipelineApplication;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=ExcelPipelineApplication.class, properties={"lab.career.sequence.enabled=true", "lab.storage.root=${java.io.tmpdir}/lab-career-api-${random.uuid}"})
@org.springframework.test.annotation.DirtiesContext
class CareerSequenceApiTest {
    @DynamicPropertySource static void worker(DynamicPropertyRegistry properties) {
        properties.add("lab.career.sequence.manifest", () -> System.getProperty("lab.sequence.manifest"));
        properties.add("lab.career.sequence.java-executable", () -> System.getProperty("lab.worker.java"));
    }
    @Autowired ScenarioRunController submission;
    @Autowired DashboardCatalogController catalog;
    @Autowired ExecutionEvidenceController evidence;
    @Autowired MetricCatalogController metrics;
    @Autowired RunEvidenceController comparisons;
    @Autowired RunStore store;
    @Autowired com.pnomeer.lab.runtime.DurableExperimentRunner runner;
    @Autowired StorageProperties storage;

    @Test void actualWorkerVariantsFlowThroughApiAndSurviveStoreReopen() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(submission, catalog, evidence, metrics, comparisons)
                .setControllerAdvice(new ScenarioExceptionHandler()).build();
        mvc.perform(get("/dashboard/v1/experiments/career.mcp-platform.sequence"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readiness").value("READY"))
                .andExpect(jsonPath("$.executionKind").value("LOCAL_JVM"))
                .andExpect(jsonPath("$.capabilities.checksProduced").value(true))
                .andExpect(jsonPath("$.capabilities.jvmObservation").value(false));
        mvc.perform(get("/dashboard/v1/capabilities")).andExpect(jsonPath("$.features.localJvm").value(true));
        List<String> ids = new ArrayList<>();
        for (String variant : List.of("BEFORE", "ATOMIC_UPDATE", "SELECT_FOR_UPDATE")) {
            String body = """
                {"schemaVersion":1,"experiment":"career.mcp-platform.sequence",
                 "scenario":{"id":"api-sequence","hypothesis":"The atomic allocator prevents overlapping ranges"},
                 "parameters":{"variant":"%s","workers":10,"blockSize":1000},"measurement":{"mode":"SINGLE_PASS"}}
                """.formatted(variant);
            var response = mvc.perform(post("/runs").contentType("application/json").content(body)).andExpect(status().isAccepted()).andReturn();
            String id = JsonMapper.builder().build().readTree(response.getResponse().getContentAsString()).path("runId").asText();
            ids.add(id);
            long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
            while (System.nanoTime() < deadline && store.find(id).orElseThrow().completedAtEpochMs() == 0) Thread.sleep(100);
            var run = store.find(id).orElseThrow();
            assertEquals(RunStatus.SUCCEEDED, run.status(), run.failureMessage());
            var execution = store.execution(id).orElseThrow();
            assertTrue(execution.evidenceComplete());
            assertTrue(execution.worker().environment().javaVersion().startsWith("21"));
            assertTrue(run.runMetadata().environment().javaVersion().startsWith("25"));
            assertEquals(2, store.checks(id, -1, 20).size());
            assertEquals(1, store.artifacts(id).size());
            assertEquals(variant.equals("BEFORE"), run.summary().counters().get("sequence.overlapPairs") > 0);
            mvc.perform(get("/dashboard/v1/runs/" + id + "/execution")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.execution.evidenceComplete").value(true));
        }
        var comparison = comparisons.compare(ids.get(0), ids.get(1));
        assertEquals("ns", comparison.values().stream().filter(v -> v.name().equals("sequence.elapsedNanos")).findFirst().orElseThrow().unit());
        runner.close();
        ((JdbcRunStore) store).close();
        try (var reopened = new JdbcRunStore(Path.of(storage.root()), 20, 4)) {
            for (String id : ids) {
                assertTrue(reopened.execution(id).orElseThrow().evidenceComplete());
                assertEquals(2, reopened.checks(id, -1, 20).size());
                assertEquals(1, reopened.artifacts(id).size());
            }
        }
    }
}
