package com.pnomeer.lab.app.series;

import com.pnomeer.lab.EngineeringLabApplication;
import com.pnomeer.lab.app.run.ScenarioExceptionHandler;
import com.pnomeer.lab.core.storage.RunSeriesStore;
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

@SpringBootTest(classes=EngineeringLabApplication.class, properties={"lab.storage.root=${java.io.tmpdir}/lab-sweep-api-${random.uuid}", "lab.series.poll-interval-ms=20"})
class RunSweepApiIntegrationTest {
    @Autowired RunSweepController controller;
    @Autowired RunSeriesStore store;
    private MockMvc mvc;
    private final JsonMapper json = JsonMapper.builder().build();
    private static String body(String grid) {
        return """
            {"definition":{"schemaVersion":1,"experiment":"concurrency.queue-contention",
              "scenario":{"id":"sweep-capacity","hypothesis":"Larger queues reduce producer waiting"},
              "parameters":{"queueCapacity":2,"producerThreads":1,"consumerThreads":1,"itemsPerProducer":20,"consumerDelayMs":1},
              "measurement":{"mode":"SINGLE_PASS"}},
             "grid":%s}
            """.formatted(grid);
    }
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ScenarioExceptionHandler()).build();
    }

    @Test void runsOneSeriesPerGridPointAndSummarizesEach() throws Exception {
        var submitted = json.readTree(mvc.perform(post("/run-sweeps?repetitions=3&warmup=1").contentType("application/json")
                .content(body("{\"queueCapacity\":[1,8]}"))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String id = submitted.get("sweepId").asString();
        JsonNode view = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            view = json.readTree(mvc.perform(get("/run-sweeps/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            String state = view.get("sweep").get("status").asString();
            if (!state.equals("QUEUED") && !state.equals("RUNNING")) break;
            Thread.sleep(50);
        }
        assertEquals("SUCCEEDED", view.get("sweep").get("status").asString());
        var points = view.get("points");
        assertEquals(2, points.size());
        assertEquals(8, points.get(1).get("parameters").get("queueCapacity").asInt());
        var second = points.get(1).get("series");
        assertEquals("sweep-capacity.p1", second.get("series").get("scenarioId").asString());
        assertEquals(4, second.get("members").size());
        assertEquals(3, second.get("measuredSucceeded").asInt());
        assertFalse(second.get("measures").isEmpty());
        mvc.perform(get("/run-sweeps")).andExpect(status().isOk()).andExpect(jsonPath("$[0].sweepId").value(id));
    }

    @Test void validatesEveryPointBeforeRunningAnything() throws Exception {
        int before = store.listSeries(null, 100).size();
        mvc.perform(post("/run-sweeps").contentType("application/json").content(body("{\"queueCapacity\":[1,0]}")))
                .andExpect(status().isBadRequest()); // 0 is below the parameter minimum
        mvc.perform(post("/run-sweeps").contentType("application/json").content(body("{\"unknown\":[1]}")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/run-sweeps").contentType("application/json").content(body("{}"))).andExpect(status().isBadRequest());
        mvc.perform(post("/run-sweeps").contentType("application/json").content("{\"grid\":{\"queueCapacity\":[1]}}"))
                .andExpect(status().isBadRequest());
        assertEquals(before, store.listSeries(null, 100).size());
        assertTrue(store.listSweeps(null, 100).isEmpty());
        mvc.perform(get("/run-sweeps/missing")).andExpect(status().isNotFound());
    }
}
