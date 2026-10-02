package com.pnomeer.lab.app.ingest;

import com.pnomeer.lab.experiments.pipeline.PipelineRunner;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = com.pnomeer.pipeline.ExcelPipelineApplication.class,
        properties = "lab.storage.root=${java.io.tmpdir}/engineering-lab-tests-${random.uuid}")
class IngestionRunMetadataIntegrationTest {
    @Autowired
    private IngestionController controller;

    @Test
    void exposesMetadataForBothExecutionModesAndFailedWorkbook() throws Exception {
        byte[] workbook = workbook();
        for (PipelineRunner.RunMode mode : PipelineRunner.RunMode.values()) {
            var file = new MockMultipartFile("file", "sample.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);
            String jobId = controller.startExcelIngestion(file, mode).jobId();
            var result = awaitCompletion(jobId);

            assertEquals(IngestionJobStatus.SUCCEEDED, result.status());
            assertEquals(1, result.insertedCount());
            assertEquals("500", result.runMetadata().parameters().get("pipeline.insert.chunk-size"));
            assertEquals("sample.xlsx", result.runMetadata().parameters().get("input.fileName"));
            assertEquals(64, result.runMetadata().parameters().get("input.sha256").length());
            assertEquals(jobId, result.runMetadata().runId());
            assertEquals(mode.name(), result.runMetadata().parameters().get("mode"));
            assertEquals(String.valueOf(workbook.length), result.runMetadata().parameters().get("payloadBytes"));
            assertEquals(System.getProperty("java.version"), result.runMetadata().environment().javaVersion());
            var listed = controller.listJobs().jobs().stream()
                    .filter(job -> job.jobId().equals(jobId)).findFirst().orElseThrow();
            assertEquals(result.runMetadata(), listed.runMetadata());
            MockMvcBuilders.standaloneSetup(controller).build()
                    .perform(get("/ingest/jobs/{jobId}", jobId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jobId").value(jobId))
                    .andExpect(jsonPath("$.runMetadata.runId").value(jobId))
                    .andExpect(jsonPath("$.runMetadata.parameters.mode").value(mode.name()))
                    .andExpect(jsonPath("$.insertedCount").value(1));
        }

        String failedId = controller.startExcelIngestion(
                new MockMultipartFile("file", "broken.xlsx", "application/octet-stream", new byte[]{1, 2, 3}),
                PipelineRunner.RunMode.PIPELINE).jobId();
        var failed = awaitCompletion(failedId);
        assertEquals(IngestionJobStatus.FAILED, failed.status());
        assertEquals(failedId, failed.runMetadata().runId());
        assertEquals("3", failed.runMetadata().parameters().get("payloadBytes"));
        assertNotNull(failed.failureMessage());
    }

    private IngestionController.IngestionJobResponse awaitCompletion(String jobId) {
        return assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            while (true) {
                var result = controller.getJob(jobId);
                if (result.status() != IngestionJobStatus.RUNNING) {
                    return result;
                }
                Thread.sleep(10);
            }
        });
    }

    private static byte[] workbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("data");
            sheet.createRow(0).createCell(0).setCellValue("header");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("first");
            row.createCell(1).setCellValue("second");
            row.createCell(2).setCellValue("3");
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
