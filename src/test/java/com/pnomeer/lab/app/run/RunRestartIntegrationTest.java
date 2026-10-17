package com.pnomeer.lab.app.run;

import com.pnomeer.pipeline.ExcelPipelineApplication;
import com.pnomeer.lab.app.experiment.ExperimentJobService;
import com.pnomeer.lab.app.ingest.IngestionService;
import com.pnomeer.lab.core.ExperimentStatus;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.experiments.concurrency.QueueContentionScenario;
import com.pnomeer.lab.experiments.pipeline.PipelineRunner;
import com.pnomeer.lab.runtime.DurableExperimentRunner;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RunRestartIntegrationTest {
    @TempDir Path root;
    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(ExcelPipelineApplication.class).web(WebApplicationType.NONE)
                .run("--lab.storage.root=" + root, "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }

    @Test
    void realApplicationRestartPreservesGenericAndExcelApisAndRecoversQueuedRuns() throws Exception {
        String genericId, excelId;
        StoredRun genericSaved, excelSaved;
        try (var app = start()) {
            var generic = app.getBean(ExperimentJobService.class);
            genericId = generic.startQueueContentionJob(new QueueContentionScenario("restart", 2, 1, 1, 8, 0));
            var ingestion = app.getBean(IngestionService.class);
            byte[] input;
            try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
                var sheet = workbook.createSheet();
                sheet.createRow(0).createCell(0).setCellValue("header");
                var row = sheet.createRow(1);
                row.createCell(0).setCellValue("a"); row.createCell(1).setCellValue("b"); row.createCell(2).setCellValue("1");
                var invalid = sheet.createRow(2);
                invalid.createCell(0).setCellValue("keep"); invalid.createCell(1).setCellValue("한글"); invalid.createCell(2).setCellValue("not-number");
                workbook.write(bytes); input = bytes.toByteArray();
            }
            excelId = ingestion.startJob(new MockMultipartFile("file", "restart.xlsx", "application/octet-stream", input), PipelineRunner.RunMode.PIPELINE);
            var store = app.getBean(RunStore.class);
            awaitCompleted(store, genericId); awaitCompleted(store, excelId);
            assertEquals(RunStatus.SUCCEEDED, store.find(excelId).orElseThrow().status());
            genericSaved = store.find(genericId).orElseThrow();
            excelSaved = store.find(excelId).orElseThrow();
            assertNotNull(genericSaved.runMetadata().parameters().get(com.pnomeer.lab.registry.measurement.MeasurementContract.PARAMETER));
            assertNotNull(excelSaved.runMetadata().parameters().get(com.pnomeer.lab.registry.measurement.MeasurementContract.PARAMETER));
            var runner = app.getBean(DurableExperimentRunner.class);
            runner.prepare(app.getBean(com.pnomeer.lab.experiments.concurrency.QueueContentionExperiment.class),
                    new QueueContentionScenario("not-dispatched", 1, 1, 1, 1, 0), "queued-before-restart");
            MockMvcBuilders.standaloneSetup(app.getBean(RunController.class)).build()
                    .perform(get("/runs/{id}/dead-letters", "queued-before-restart")).andExpect(status().isConflict());
        }
        try (var app = start()) {
            var store = app.getBean(RunStore.class);
            MockMvcBuilders.standaloneSetup(app.getBean(PatternCatalogController.class)).build()
                    .perform(get("/dashboard/v1/pattern-catalog")).andExpect(status().isOk());
            assertEquals(genericSaved, store.find(genericId).orElseThrow());
            assertEquals(excelSaved, store.find(excelId).orElseThrow());
            assertEquals(RunStatus.INTERRUPTED, store.find("queued-before-restart").orElseThrow().status());
            var generic = app.getBean(ExperimentJobService.class).getJob(genericId);
            assertEquals(ExperimentStatus.SUCCEEDED, generic.getStatus());
            assertEquals(8L, generic.getSummary().counters().get("items.consumed"));
            assertFalse(generic.getTimeline().isEmpty());
            var excel = app.getBean(IngestionService.class).getJob(excelId);
            assertEquals(1, excel.getInsertedCount());
            assertEquals("restart.xlsx", excel.getFileName());
            assertEquals(64, excel.getRunMetadata().parameters().get("input.sha256").length());
            assertTrue(excel.getCompletedAtEpochMs() >= excel.getStartedAtEpochMs());
            var mvc = MockMvcBuilders.standaloneSetup(app.getBean(RunController.class)).build();
            mvc.perform(get("/runs/{id}/dead-letters", excelId).param("limit", "1")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.runId").value(excelId)).andExpect(jsonPath("$.rows[0].rowIndex").value(2))
                    .andExpect(jsonPath("$.rows[0].stage").value("validation"))
                    .andExpect(jsonPath("$.rows[0].reason").value("col3 must be integer"))
                    .andExpect(jsonPath("$.rows[0].cells[1]").value("한글"))
                    .andExpect(jsonPath("$.nextAfter").value(2));
            mvc.perform(get("/runs/{id}/dead-letters", excelId).param("after", "2")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows.length()").value(0));
            mvc.perform(get("/runs/{id}/dead-letters", excelId).param("limit", "101")).andExpect(status().isBadRequest());
            mvc.perform(get("/runs/{id}/dead-letters", excelId).param("after", "-2")).andExpect(status().isBadRequest());
            mvc.perform(get("/runs/{id}/dead-letters", "missing")).andExpect(status().isNotFound());
            mvc.perform(get("/runs/{id}/dead-letters", genericId)).andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(0));
            mvc.perform(get("/runs/{id}", genericId)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.runId").value(genericId))
                    .andExpect(jsonPath("$.summary.counters['items.consumed']").value(8));
            mvc.perform(get("/runs").param("limit", "1")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.runs.length()").value(1)).andExpect(jsonPath("$.nextBefore").isNumber());
            mvc.perform(get("/runs/{id}/metrics", genericId).param("limit", "1")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.metrics.length()").value(1)).andExpect(jsonPath("$.nextAfter").value(0));
            mvc.perform(get("/runs").param("limit", "501")).andExpect(status().isBadRequest());
            mvc.perform(get("/runs/{id}", "missing")).andExpect(status().isNotFound());
        }
    }
    private void awaitCompleted(RunStore store, String id) {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            while (true) {
                var status = store.find(id).orElseThrow().status();
                if (status != RunStatus.QUEUED && status != RunStatus.RUNNING) return;
                Thread.sleep(20);
            }
        });
    }
}
