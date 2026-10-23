package com.pnomeer.lab.app.run;

import com.pnomeer.lab.EngineeringLabApplication;
import com.pnomeer.lab.app.series.RunSeriesService;
import com.pnomeer.lab.core.storage.RunStatus;
import com.pnomeer.lab.scenario.ScenarioDocuments;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = EngineeringLabApplication.class, properties = {"lab.storage.root=${java.io.tmpdir}/lab-bundle-${random.uuid}", "lab.series.poll-interval-ms=20"})
class EvidenceBundleIntegrationTest {
    private static final String QUEUE = """
            {"schemaVersion":1,"experiment":"concurrency.queue-contention",
            "scenario":{"id":"bundle-queue","hypothesis":"Bundles keep every repetition"},
            "parameters":{"queueCapacity":2,"producerThreads":1,"consumerThreads":1,"itemsPerProducer":10,"consumerDelayMs":1},
            "measurement":{"mode":"SINGLE_PASS"}}
            """;
    @Autowired WebApplicationContext context;
    @Autowired RunSeriesService series;
    @Autowired ScenarioDocuments documents;
    private MockMvc mvc;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).build(); }

    private String finishedSeries() throws Exception {
        var definition = documents.read(new ByteArrayInputStream(QUEUE.getBytes(StandardCharsets.UTF_8)), ScenarioDocuments.Format.JSON);
        String id = series.submit(definition, null, 3, 1).seriesId();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            var status = series.view(id).orElseThrow().series().status();
            if (status != RunStatus.QUEUED && status != RunStatus.RUNNING) return id;
            Thread.sleep(20);
        }
        throw new AssertionError("series did not finish");
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) entries.put(entry.getName(), in.readAllBytes());
        }
        return entries;
    }

    @Test void bundlesASeriesWithEveryRunExportAManifestAndMatchingChecksums() throws Exception {
        String id = finishedSeries();
        byte[] body = mvc.perform(get("/evidence-bundles").param("series", id)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip")).andReturn().getResponse().getContentAsByteArray();
        var files = unzip(body);
        assertTrue(files.containsKey("series/" + id + ".json"));
        assertEquals(4, files.keySet().stream().filter(n -> n.startsWith("runs/")).count(), "warmup plus three repetitions");
        assertFalse(files.containsKey("FINDING-DRAFT.md"));
        JsonNode manifest = json.readTree(files.get("manifest.json"));
        assertEquals(4, manifest.get("runs").size());
        var sums = new String(files.get("SHA256SUMS"), StandardCharsets.UTF_8).lines().toList();
        assertEquals(files.size() - 1, sums.size());
        for (String line : sums) {
            String[] parts = line.split("  ", 2);
            assertEquals(parts[0], HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(files.get(parts[1]))), parts[1]);
        }
        String export = new String(files.get(sums.stream().filter(l -> l.contains("runs/")).findFirst().orElseThrow().split("  ")[1]), StandardCharsets.UTF_8);
        assertTrue(export.lines().toList().getLast().contains("\"type\":\"end\""));
    }

    @Test void postStoresTheDraftAndTwoRunsGetAComparison() throws Exception {
        String id = finishedSeries();
        var members = series.view(id).orElseThrow().members();
        byte[] body = mvc.perform(post("/evidence-bundles").param("run", members.get(1).runId()).param("run", members.get(2).runId())
                .contentType("text/markdown").content("# Finding: draft")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var files = unzip(body);
        assertEquals("# Finding: draft", new String(files.get("FINDING-DRAFT.md"), StandardCharsets.UTF_8));
        assertTrue(files.containsKey("comparison.json"));
        assertNotNull(json.readTree(files.get("comparison.json")).get("conditions"));
    }

    @Test void rejectsEmptyAndUnknownSelectionsBeforeStreaming() throws Exception {
        mvc.perform(get("/evidence-bundles")).andExpect(status().isBadRequest());
        mvc.perform(get("/evidence-bundles").param("run", "missing")).andExpect(status().isNotFound());
        mvc.perform(get("/evidence-bundles").param("series", "a").param("series", "b").param("series", "c")).andExpect(status().isBadRequest());
    }
}
