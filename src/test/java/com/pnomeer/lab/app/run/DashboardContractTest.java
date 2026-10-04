package com.pnomeer.lab.app.run;

import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DashboardContractTest {
    @Test void pinsReadEvidenceWireShapesCursorsErrorsAndExportHeaders() throws Exception {
        var json = JsonMapper.builder().build();
        var fixture = json.readTree(Files.readString(Path.of("contracts/dashboard/v1/fixtures/evidence.json")));
        var run = json.readValue(fixture.get("run").toString(), StoredRun.class);
        var metric = json.readValue(fixture.get("metrics").get("metrics").get(0).toString(), StoredMetric.class);
        var artifact = json.readValue(fixture.get("artifacts").get(0).toString(), ArtifactReference.class);
        var store = mock(RunStore.class);
        when(store.find(run.runId())).thenReturn(Optional.of(run));
        when(store.list(null, Long.MAX_VALUE, 1)).thenReturn(List.of(run));
        when(store.metrics(run.runId(), -1, 1)).thenReturn(List.of(metric));
        when(store.metrics(run.runId(), 0, 1)).thenReturn(List.of());
        when(store.artifacts(run.runId())).thenReturn(List.of(artifact));
        var properties = new StorageProperties("unused",2,1,10,2,1,1000,1,1,2,".",100);
        var mvc = MockMvcBuilders.standaloneSetup(new RunController(store, properties), new RunEvidenceController(store, properties)).build();
        for (var entry : Map.of("/runs?limit=1", "page", "/runs/"+run.runId(), "run",
                "/runs/"+run.runId()+"/metrics?limit=1", "metrics", "/runs/"+run.runId()+"/artifacts", "artifacts").entrySet()) {
            var result = mvc.perform(get(entry.getKey())).andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("application/json")).andReturn();
            assertEquals(fixture.get(entry.getValue()), json.readTree(result.getResponse().getContentAsString()));
        }
        mvc.perform(get("/runs/"+run.runId()+"/metrics?after=0&limit=1"))
                .andExpect(jsonPath("$.metrics.length()").value(0)).andExpect(jsonPath("$.nextAfter").isEmpty());
        mvc.perform(get("/runs/missing")).andExpect(status().isNotFound());
        mvc.perform(get("/runs?limit=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/runs/compare").param("baseline",run.runId()).param("candidate",run.runId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.values[0].unit").value("items"))
                .andExpect(jsonPath("$.conditions").isArray()).andExpect(jsonPath("$.cautions").isArray());
        var output = mvc.perform(get("/runs/"+run.runId()+"/export"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
                .andExpect(header().string("Content-Disposition","attachment; filename=run-evidence.ndjson"))
                .andReturn().getResponse().getContentAsString().lines().map(json::readTree).toList();
        assertEquals("run",output.getFirst().get("type").asText());
        assertEquals("end",output.getLast().get("type").asText());
        assertEquals(1,output.getLast().get("metricCount").asInt());
        assertEquals(1,output.getLast().get("artifactCount").asInt());
    }
}
