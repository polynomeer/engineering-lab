package com.pnomeer.lab.experiments.examples;

import com.pnomeer.pipeline.ExcelPipelineApplication;
import com.pnomeer.lab.app.run.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.registry.measurement.MeasurementContract;
import com.pnomeer.lab.scenario.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChecksumExtensionIntegrationTest {
    @TempDir Path root;
    private ConfigurableApplicationContext start(boolean enabled) {
        return new SpringApplicationBuilder(ExcelPipelineApplication.class).web(WebApplicationType.NONE).run(
                "--lab.examples.checksum.enabled="+enabled,"--lab.storage.root="+root,"--spring.main.banner-mode=off","--logging.level.root=WARN");
    }
    @Test void independentContributionsReachGenericApisAndStoredEvidenceSurvivesDisablingExtension() throws Exception {
        var json=JsonMapper.builder().build();
        StoredRun saved;
        try (var app=start(true)) {
            var mvc=MockMvcBuilders.standaloneSetup(app.getBean(ScenarioRunController.class),app.getBean(ExperimentCatalogController.class),
                    app.getBean(PatternCatalogController.class),app.getBean(StudyCatalogController.class),app.getBean(MeasurementController.class),
                    app.getBean(RunEvidenceController.class)).setControllerAdvice(new ScenarioExceptionHandler()).build();
            mvc.perform(get("/experiments")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
            String patterns=mvc.perform(get("/dashboard/v1/pattern-catalog")).andReturn().getResponse().getContentAsString();
            assertTrue(patterns.contains(BatchedChecksumExperiment.ID)); assertTrue(patterns.contains("chunked-write"));
            var studies=json.readTree(mvc.perform(get("/dashboard/v1/study-catalog")).andReturn().getResponse().getContentAsString());
            String success=null, failure=null;
            for (var study : studies.path("studies")) if (study.path("experimentId").asText().equals(BatchedChecksumExperiment.ID)) {
                assertEquals(3,study.path("presets").size());
                for (var preset : study.path("presets")) {
                    var original=Files.readString(Path.of(preset.path("sourcePath").asText()));
                    var response=mvc.perform(post("/runs").contentType("application/json").content(original)).andExpect(status().isAccepted()).andReturn();
                    var submission=json.readTree(response.getResponse().getContentAsString());
                    assertEquals(preset.path("definitionSha256").asText(),submission.path("definitionSha256").asText());
                    String id=submission.path("runId").asText();
                    var store=app.getBean(RunStore.class);
                    assertTimeoutPreemptively(Duration.ofSeconds(10),()->{
                        while (store.find(id).orElseThrow().completedAtEpochMs()==0) Thread.sleep(10);
                    });
                    var run=store.find(id).orElseThrow();
                    assertTrue(MeasurementContract.read(run.runMetadata().parameters().get(MeasurementContract.PARAMETER),run.experimentType()).isPresent());
                    if (preset.path("id").asText().equals("sink-failure")) {
                        assertEquals(RunStatus.FAILED,run.status()); failure=id;
                        assertTrue(store.metrics(id,-1,100).stream().anyMatch(p -> p.point().counters().getOrDefault("checksum.rows",0L)==8));
                    } else {
                        assertEquals(RunStatus.SUCCEEDED,run.status()); success=id;
                        assertEquals(23L,run.summary().counters().get("checksum.rows"));
                        assertEquals(414L,run.summary().counters().get("checksum.value"));
                    }
                    mvc.perform(get("/runs/"+id+"/export")).andExpect(status().isOk());
                }
            }
            assertNotNull(success); assertNotNull(failure);
            mvc.perform(get("/dashboard/v1/comparison-assessment").param("baseline",success).param("candidate",failure))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REVIEW_REQUIRED"));
            saved=app.getBean(RunStore.class).find(success).orElseThrow();
        }
        try (var app=start(false)) {
            assertEquals(saved,app.getBean(RunStore.class).find(saved.runId()).orElseThrow());
            assertEquals(3,app.getBean(com.pnomeer.lab.registry.ExperimentCatalog.class).experiments().size());
            assertEquals(saved.runMetadata().parameters().get(MeasurementContract.PARAMETER),
                    app.getBean(MeasurementController.class).compare(saved.runId(),saved.runId()).baselineContract().encode());
        }
    }
}
