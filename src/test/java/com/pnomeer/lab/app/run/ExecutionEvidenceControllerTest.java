package com.pnomeer.lab.app.run;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExecutionEvidenceControllerTest {
    @TempDir Path root;
    private final JsonMapper json = JsonMapper.builder().build();
    private StorageProperties properties() { return new StorageProperties(root.toString(), 2, 2, 10, 2, 1, 1000, 2, 2, 2, ".", 100); }
    private void create(JdbcRunStore store, String id) {
        store.create("test", "small", new RunMetadata(id, Map.of(), RunEnvironment.capture(), new GitProvenance("controller", false, null)));
        store.start(id);
    }
    private void external(JdbcRunStore store, String id, boolean known) {
        create(store, id);
        store.registerExecution(id, new RunExecution.Definition(RunExecution.Kind.LOCAL_JVM, "career.test", 1, RunExecution.MeasurementScope.WORKER_PROCESS));
        if (known) store.registerWorker(id, new RunExecution.Worker(
                new RunEnvironment("21", "worker", "vm", "os", "version", "arch", 1, 1024),
                new GitProvenance("worker", true, null), "a".repeat(64)));
    }
    @Test void exposesMissingEvidencePagesAndVersionedExportAfterReopenWhileKeepingOldExport() throws Exception {
        try (var store = new JdbcRunStore(root, 2, 2)) {
            external(store, "worker", true);
            for (int i=0; i<3; i++) store.appendCheck("worker", new RunCheck(i, "check-"+i, RunCheck.Status.PASS, "1", "1", "checked"));
            store.complete("worker", RunStatus.FAILED, ExperimentSummary.empty(), "partial");
            create(store, "legacy"); store.complete("legacy", RunStatus.SUCCEEDED, ExperimentSummary.empty(), null);
        }
        try (var store = new JdbcRunStore(root, 2, 2)) {
            var mvc = MockMvcBuilders.standaloneSetup(new ExecutionEvidenceController(store, properties()), new RunEvidenceController(store, properties())).build();
            mvc.perform(get("/dashboard/v1/runs/legacy/execution")).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false));
            mvc.perform(get("/dashboard/v1/runs/worker/execution")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.execution.worker.environment.javaVersion").value("21"))
                    .andExpect(jsonPath("$.execution.evidenceComplete").value(false));
            mvc.perform(get("/dashboard/v1/runs/worker/checks")).andExpect(status().isOk()).andExpect(jsonPath("$.checks.length()").value(2)).andExpect(jsonPath("$.nextAfter").value(1));
            mvc.perform(get("/dashboard/v1/runs/worker/checks?after=1")).andExpect(status().isOk()).andExpect(jsonPath("$.checks[0].sequence").value(2)).andExpect(jsonPath("$.nextAfter").isEmpty());
            mvc.perform(get("/dashboard/v1/runs/worker/checks?limit=3")).andExpect(status().isBadRequest());
            mvc.perform(get("/dashboard/v1/runs/worker/checks?after=-2")).andExpect(status().isBadRequest());
            mvc.perform(get("/dashboard/v1/runs/missing/execution")).andExpect(status().isNotFound());
            var text = mvc.perform(get("/dashboard/v1/runs/worker/export")).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Content-Disposition", "attachment; filename=run-evidence-v2.ndjson"))
                    .andReturn().getResponse().getContentAsString();
            var lines = text.lines().map(json::readTree).toList();
            assertEquals(2, lines.getFirst().get("schemaVersion").asInt());
            assertEquals(3, lines.stream().filter(line -> "check".equals(line.get("type").asText())).count());
            assertEquals(3, lines.getLast().get("checkCount").asInt());
            String old = mvc.perform(get("/runs/worker/export")).andReturn().getResponse().getContentAsString();
            assertEquals(1, json.readTree(old.lines().findFirst().orElseThrow()).get("schemaVersion").asInt());
            assertFalse(old.contains("\"type\":\"execution\""));
        }
    }
    @Test void activeRunsExposePhaseButRejectCheckPagingAndExport() throws Exception {
        try (var store = new JdbcRunStore(root, 2, 2)) {
            external(store, "active", false);
            var mvc = MockMvcBuilders.standaloneSetup(new ExecutionEvidenceController(store, properties())).build();
            mvc.perform(get("/dashboard/v1/runs/active/execution")).andExpect(status().isOk()).andExpect(jsonPath("$.execution.phase").value("PREPARING"));
            mvc.perform(get("/dashboard/v1/runs/active/checks")).andExpect(status().isConflict());
            mvc.perform(get("/dashboard/v1/runs/active/export")).andExpect(status().isConflict());
        }
    }
    @Test void oldComparisonShapeIncludesWorkerDifferencesWithoutSubstitutingControllerEnvironment() throws Exception {
        try (var store = new JdbcRunStore(root, 2, 2)) {
            external(store, "known", true); external(store, "unknown", false);
            var mvc = MockMvcBuilders.standaloneSetup(new RunEvidenceController(store, properties())).build();
            String body = mvc.perform(get("/runs/compare?baseline=known&candidate=unknown")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var comparison = json.readValue(body, RunComparison.class);
            var environment = comparison.conditions().stream().filter(c -> c.field().equals("execution.environment")).findFirst().orElseThrow();
            assertEquals("21", ((Map<?,?>)environment.baseline()).get("javaVersion"));
            assertNull(environment.candidate());
            assertTrue(comparison.cautions().stream().anyMatch(c -> c.contains("Worker identity is unknown")));
            assertTrue(comparison.cautions().stream().anyMatch(c -> c.contains("Worker Git")));
            assertTrue(comparison.cautions().stream().anyMatch(c -> c.contains("artifact")));
        }
    }
}
