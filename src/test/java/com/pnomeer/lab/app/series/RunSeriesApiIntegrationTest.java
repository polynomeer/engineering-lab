package com.pnomeer.lab.app.series;

import com.pnomeer.lab.EngineeringLabApplication;
import com.pnomeer.lab.app.run.ScenarioExceptionHandler;
import com.pnomeer.lab.core.storage.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=EngineeringLabApplication.class, properties={"lab.storage.root=${java.io.tmpdir}/lab-series-api-${random.uuid}", "lab.series.poll-interval-ms=20"})
class RunSeriesApiIntegrationTest {
    @Autowired RunSeriesController controller;
    @Autowired RunStore runs;
    @Autowired com.pnomeer.lab.app.run.ScenarioRunService scenarioRuns;
    @Autowired com.pnomeer.lab.scenario.ScenarioDocuments documents;
    private MockMvc mvc;
    private final JsonMapper json = JsonMapper.builder().build();
    private static String queue(int capacity) {
        return """
            {"schemaVersion":1,"experiment":"concurrency.queue-contention",
            "scenario":{"id":"series-capacity-%d","hypothesis":"Capacity changes producer waiting"},
            "parameters":{"queueCapacity":%d,"producerThreads":1,"consumerThreads":1,"itemsPerProducer":20,"consumerDelayMs":1},
            "measurement":{"mode":"SINGLE_PASS"}}
            """.formatted(capacity, capacity);
    }
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ScenarioExceptionHandler()).build();
    }
    private String submit(String body, String query) throws Exception {
        var response = mvc.perform(post("/run-series" + query).contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(header().exists("Location")).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("seriesId").asString();
    }
    private JsonNode finished(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            var view = json.readTree(mvc.perform(get("/run-series/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            String state = view.get("series").get("status").asString();
            if (!state.equals("QUEUED") && !state.equals("RUNNING")) return view;
            Thread.sleep(50);
        }
        throw new AssertionError("Series did not finish: " + id);
    }

    @Test void rejectsOtherSubmissionsWhileASeriesMeasures() throws Exception {
        String slow = queue(2).replace("\"itemsPerProducer\":20,\"consumerDelayMs\":1", "\"itemsPerProducer\":40,\"consumerDelayMs\":10");
        String id = submit(slow, "?repetitions=2&warmup=0");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!json.readTree(mvc.perform(get("/run-series/" + id)).andReturn().getResponse().getContentAsString())
                .get("series").get("status").asString().equals("RUNNING")) {
            assertTrue(System.nanoTime() < deadline, "series never started");
            Thread.sleep(10);
        }
        var definition = documents.read(new java.io.ByteArrayInputStream(queue(8).getBytes()), com.pnomeer.lab.scenario.ScenarioDocuments.Format.JSON);
        assertThrows(com.pnomeer.lab.runtime.DurableExperimentRunner.MeasurementReserved.class, () -> scenarioRuns.submit(definition, null));
        var view = finished(id);
        assertEquals("SUCCEEDED", view.get("series").get("status").asString());
        assertEquals(0, view.get("overlappingRunIds").size());
        // The reservation ends with the series.
        assertNotNull(scenarioRuns.submit(definition, null));
    }

    @Test void runsWarmupThenRepetitionsAndSummarizesOnlyMeasuredRuns() throws Exception {
        String id = submit(queue(4), "?repetitions=3&warmup=1");
        var view = finished(id);
        assertEquals("SUCCEEDED", view.get("series").get("status").asString());
        var members = view.get("members");
        assertEquals(4, members.size());
        assertTrue(members.get(0).get("warmup").asBoolean());
        assertFalse(members.get(3).get("warmup").asBoolean());
        assertEquals(3, view.get("measuredSucceeded").asInt());
        assertEquals(0.1, view.get("stability").get("variationThreshold").asDouble(), 1e-9);
        assertTrue(view.get("stability").get("highVariationRoles").isArray());
        // Every repetition is an ordinary Run with the same definition hash and no series label in its parameters.
        var first = runs.find(members.get(1).get("runId").asString()).orElseThrow();
        var last = runs.find(members.get(3).get("runId").asString()).orElseThrow();
        assertEquals(first.runMetadata().parameters().get("scenario.definition.sha256"), last.runMetadata().parameters().get("scenario.definition.sha256"));
        assertTrue(first.runMetadata().parameters().keySet().stream().noneMatch(k -> k.startsWith("series")));
        var throughput = java.util.stream.StreamSupport.stream(view.get("measures").spliterator(), false)
                .filter(m -> m.get("role").asString().equals("throughput")).findFirst().orElseThrow();
        assertEquals("throughput.itemsPerSec", throughput.get("name").asString());
        assertEquals(3, throughput.get("statistics").get("count").asInt());
        assertTrue(throughput.get("statistics").get("median").asDouble() > 0);

        String other = submit(queue(1), "?repetitions=3&warmup=0");
        finished(other);
        var comparison = json.readTree(mvc.perform(get("/run-series/compare").param("baseline", id).param("candidate", other))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertFalse(comparison.get("sameDefinition").asBoolean());
        var row = comparison.get("measures").get(0);
        assertNotNull(row.get("separation").asString());
        assertEquals(3, row.get("baseline").get("count").asInt());

        // A baseline needs three measured repetitions; the check compares other series of the same scenario.
        mvc.perform(get("/run-series/" + id + "/regression")).andExpect(status().isOk()).andExpect(jsonPath("$.overall").value("NO_BASELINE"));
        mvc.perform(put("/run-series/" + id + "/baseline")).andExpect(status().isOk());
        mvc.perform(get("/run-series/baselines")).andExpect(status().isOk()).andExpect(jsonPath("$[0].seriesId").value(id));
        mvc.perform(get("/run-series/" + id + "/regression")).andExpect(jsonPath("$.overall").value("NO_BASELINE")); // never against itself
        String repeat = submit(queue(4), "?repetitions=3&warmup=0");
        finished(repeat);
        mvc.perform(get("/run-series/" + repeat + "/regression").param("tolerancePct", "100")).andExpect(status().isOk())
                .andExpect(jsonPath("$.baselineSeriesId").value(id)).andExpect(jsonPath("$.overall").value("PASS"));
        mvc.perform(get("/run-series/" + repeat + "/regression").param("tolerancePct", "101")).andExpect(status().isBadRequest());
        String short_ = submit(queue(4), "?repetitions=2&warmup=0");
        finished(short_);
        mvc.perform(put("/run-series/" + short_ + "/baseline")).andExpect(status().isConflict());

        mvc.perform(get("/run-series").param("experimentType", "concurrency.queue-contention"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].seriesId").value(short_)).andExpect(jsonPath("$[2].seriesId").value(other));
    }

    @Test void rejectsInvalidCountsDefinitionsAndUnknownSeries() throws Exception {
        mvc.perform(post("/run-series?repetitions=0").contentType("application/json").content(queue(2))).andExpect(status().isBadRequest());
        mvc.perform(post("/run-series?repetitions=3&warmup=11").contentType("application/json").content(queue(2))).andExpect(status().isBadRequest());
        mvc.perform(post("/run-series").contentType("application/json").content(queue(2).replace("\"queueCapacity\":2", "\"unknown\":2")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/run-series/missing")).andExpect(status().isNotFound());
        mvc.perform(get("/run-series").param("limit", "0")).andExpect(status().isBadRequest());
    }
}
