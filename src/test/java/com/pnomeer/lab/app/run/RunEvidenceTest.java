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

class RunEvidenceTest {
    @TempDir Path root;
    private StorageProperties properties() { return new StorageProperties(root.toString(), 2, 2, 10, 2, 1, 1000, 2, 2, 2, ".", 100); }
    private void create(JdbcRunStore store, String id, Map<String,String> params, RunStatus status) {
        store.create("concurrency.queue-contention", "test", new RunMetadata(id, params, RunEnvironment.capture(), new GitProvenance("abc", false, null)));
        store.start(id);
        if (status != RunStatus.RUNNING) store.complete(id, status,
                new ExperimentSummary(Map.of("items.consumed", 10L), Map.of("queue.avgEnqueueWaitMs", 1.5), Map.of()), null);
    }
    @Test void comparesAndExportsAllPagesAndReferencesAfterReopen() throws Exception {
        var reference = new ArtifactReference("profile", "a", "profiles/a.jfr", "application/octet-stream", 123, "a".repeat(64));
        try (var store = new JdbcRunStore(root, 2, 2)) {
            create(store, "a", Map.of("queueCapacity", "1", "measurement.mode", "SINGLE_PASS"), RunStatus.SUCCEEDED);
            create(store, "b", Map.of("queueCapacity", "2", "measurement.mode", "SINGLE_PASS"), RunStatus.SUCCEEDED);
            for (int i = 0; i < 5; i++) store.appendMetrics("a", List.of(new StoredMetric(i, new MetricPoint(i, Map.of(), Map.of("n", (long)i), Map.of()))));
            store.registerArtifact(reference); store.registerArtifact(reference);
        }
        try (var store = new JdbcRunStore(root, 2, 2)) {
            var mvc = MockMvcBuilders.standaloneSetup(new RunEvidenceController(store, properties())).build();
            mvc.perform(get("/runs/compare").param("baseline", "a").param("candidate", "b"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.baseline.runId").value("a"))
                    .andExpect(jsonPath("$.values[0].unit").value("items"))
                    .andExpect(jsonPath("$.conditions[?(@.field == 'parameters.queueCapacity')].different").value(true));
            mvc.perform(get("/runs/a/artifacts")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
            String output = mvc.perform(get("/runs/a/export")).andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", "attachment; filename=run-evidence.ndjson"))
                    .andReturn().getResponse().getContentAsString();
            var lines = output.lines().map(line -> JsonMapper.builder().build().readTree(line)).toList();
            assertEquals(8, lines.size());
            assertEquals("run", lines.getFirst().get("type").asText());
            assertEquals(4, lines.get(5).get("data").get("sequence").asInt());
            assertEquals("artifact-reference", lines.get(6).get("type").asText());
            assertEquals(5, lines.getLast().get("metricCount").asInt());
            assertEquals("end", lines.getLast().get("type").asText());
        }
    }
    @Test void handlesMissingActiveAndMalformedReferences() throws Exception {
        try (var store = new JdbcRunStore(root, 2, 2)) {
            create(store, "a", Map.of(), RunStatus.RUNNING);
            var mvc = MockMvcBuilders.standaloneSetup(new RunEvidenceController(store, properties())).build();
            mvc.perform(get("/runs/a/export")).andExpect(status().isConflict());
            mvc.perform(get("/runs/missing/artifacts")).andExpect(status().isNotFound());
            mvc.perform(get("/runs/compare").param("baseline", "a").param("candidate", "missing")).andExpect(status().isNotFound());
            for (String body : List.of("null", "{}", "{\"relativePath\":\"../secret\",\"mediaType\":\"text/plain\",\"byteSize\":0,\"sha256\":\"" + "a".repeat(64) + "\"}")) {
                mvc.perform(put("/runs/a/artifacts/x").contentType("application/json").content(body)).andExpect(status().isBadRequest());
            }
            mvc.perform(put("/runs/a/artifacts/x").contentType("application/json").content(" ".repeat(8193))).andExpect(status().isPayloadTooLarge());
            String body = "{\"relativePath\":\"profile.jfr\",\"mediaType\":\"application/octet-stream\",\"byteSize\":10,\"sha256\":\"" + "a".repeat(64) + "\"}";
            mvc.perform(put("/runs/a/artifacts/x").contentType("application/json").content(body)).andExpect(status().isOk());
            mvc.perform(put("/runs/a/artifacts/x").contentType("application/json").content(body)).andExpect(status().isOk());
            mvc.perform(put("/runs/a/artifacts/x").contentType("application/json").content(body.replace("10", "11"))).andExpect(status().isBadRequest());
        }
    }
    @Test void boundsReferencesAndPreservesImmutableMetadata() {
        try (var store = new JdbcRunStore(root, 2, 2)) {
            create(store, "a", Map.of(), RunStatus.SUCCEEDED);
            create(store, "b", Map.of(), RunStatus.SUCCEEDED);
            for (int i=0; i<100; i++) store.registerArtifact(new ArtifactReference("ref"+i, "a", "a.txt", "text/plain", 0, "a".repeat(64)));
            assertThrows(IllegalArgumentException.class, () -> store.registerArtifact(new ArtifactReference("extra", "a", "b.txt", "text/plain", 0, "b".repeat(64))));
            assertThrows(IllegalArgumentException.class, () -> store.registerArtifact(new ArtifactReference("ref0", "b", "b.txt", "text/plain", 0, "b".repeat(64))));
            assertEquals(100, store.artifacts("a").size());
        }
    }
    @Test void preservesMissingValuesAndWarnsAboutIncompleteOrUnknownEvidence() {
        try (var store = new JdbcRunStore(root, 2, 2)) {
            create(store, "a", Map.of(), RunStatus.SUCCEEDED);
            create(store, "b", Map.of(), RunStatus.RUNNING);
            var result = RunComparison.of(store.find("a").orElseThrow(), store.find("b").orElseThrow());
            assertNull(result.values().getFirst().candidate());
            assertTrue(result.cautions().stream().anyMatch(s -> s.contains("not successful")));
            assertTrue(result.cautions().stream().anyMatch(s -> s.contains("not recorded")));
            assertEquals("unknown", RunComparison.unit("invented.latencyMs"));
        }
    }
}
