package com.pnomeer.lab.core.input;

import com.pnomeer.lab.app.run.ScenarioRunService;
import com.pnomeer.lab.app.ingest.IngestionService;
import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.scenario.*;
import com.pnomeer.lab.runtime.DurableExperimentRunner;
import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.experiments.pipeline.*;
import com.pnomeer.lab.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InputOwnershipTest {
    @TempDir Path root;
    @Test void invalidScenarioReleasesOwnedPayloadBeforeRunCreation() throws Exception {
        var registry = mock(ExperimentRegistry.class);
        when(registry.bind(any(), any(), any())).thenThrow(new ScenarioException("invalid input checksum"));
        var runner = mock(DurableExperimentRunner.class);
        var service = new ScenarioRunService(registry, mock(ScenarioDocuments.class), runner, Runnable::run, Runnable::run);
        var payload = InputPayload.stage(new ByteArrayInputStream(new byte[]{1}), 10);
        assertThrows(ScenarioException.class, () -> service.submit(mock(ScenarioDefinition.class), payload));
        assertThrows(IOException.class, payload::openStream);
        verifyNoInteractions(runner);
    }
    @Test void legacyUploadNeverCallsGetBytesAndReleasesFilesOnRejectionAndCompletion() throws Exception {
        var experiment = mock(PipelineExperiment.class);
        when(experiment.type()).thenReturn("pipeline.excel-ingestion");
        when(experiment.parameters(any())).thenReturn(Map.of());
        when(experiment.execute(any(), any(), any(), any(), any())).thenReturn(ExperimentSummary.empty());
        try (var store = new JdbcRunStore(root, 100, 4);
             var runner = new DurableExperimentRunner(store, () -> new GitProvenance("test", false, null), new MetricWriterOptions(8,4,20,2,1,2000), 5,2)) {
            var file = new MockMultipartFile("file", "test.xlsx", "application/octet-stream", new byte[]{1,2,3}) {
                @Override public byte[] getBytes() { throw new AssertionError("Whole-file read is forbidden"); }
            };
            for (boolean reject : new boolean[]{true, false}) {
                var service = new IngestionService(runner, store, mock(StorageProperties.class), experiment,
                        command -> { if (reject) throw new TaskRejectedException("injected"); command.run(); });
                for (int i=0; i<InputPayload.MAX_STAGED+1; i++) {
                    if (reject) assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.startJob(file, PipelineRunner.RunMode.PIPELINE));
                    else assertEquals(RunStatus.SUCCEEDED, store.find(service.startJob(file, PipelineRunner.RunMode.PIPELINE)).orElseThrow().status());
                }
            }
        }
    }
    @Test void sourceCloseFailureReleasesFileAndSlot() throws Exception {
        for (int i = 0; i <= InputPayload.MAX_STAGED; i++) {
            assertThrows(IOException.class, () -> InputPayload.stage(new ByteArrayInputStream(new byte[]{1}) {
                @Override public void close() throws IOException { throw new IOException("injected close failure"); }
            }, 10));
        }
        try (var input = InputPayload.stage(new ByteArrayInputStream(new byte[]{1}), 10)) { assertEquals(1, input.size()); }
    }
}
