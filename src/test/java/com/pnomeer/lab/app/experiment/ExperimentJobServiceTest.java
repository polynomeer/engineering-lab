package com.pnomeer.lab.app.experiment;

import com.pnomeer.lab.core.GitProvenance;
import com.pnomeer.lab.runtime.DurableExperimentRunner;
import com.pnomeer.lab.storage.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import com.pnomeer.lab.experiments.concurrency.QueueContentionExperiment;
import com.pnomeer.lab.experiments.concurrency.QueueContentionScenario;
import com.pnomeer.lab.experiments.io.IoEndpointComparisonExperiment;
import com.pnomeer.lab.experiments.io.IoEndpointComparisonScenario;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.concurrent.Executors;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ExperimentJobServiceTest {
    @TempDir Path root;
    private StorageProperties properties() {
        return new StorageProperties(root.toString(), 100, 100, 100, 3, 1, 2000, 500, 200, 8, ".", 1000);
    }

    @Test
    void runsQueueContentionExperimentAndStoresSummary() throws Exception {
        try (var store = new JdbcRunStore(root, 500, 100);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ExperimentJobService service = new ExperimentJobService(
                    new DurableExperimentRunner(store, () -> GitProvenance.unknown("test"), properties().writerOptions(), 200, 8),
                    store, properties(),
                    new QueueContentionExperiment(),
                    new IoEndpointComparisonExperiment(executor),
                    new SyncTaskExecutor());

            String jobId = service.startQueueContentionJob(new QueueContentionScenario(
                    "api-small",
                    2,
                    2,
                    1,
                    20,
                    1L));

            ExperimentJobState state = service.getJob(jobId);

            assertNotNull(state);
            assertEquals(jobId, state.getRunMetadata().runId());
            ExperimentController controller = new ExperimentController(service);
            assertEquals(state.getRunMetadata(), controller.getJob(jobId).runMetadata());
            assertEquals(state.getRunMetadata(), controller.listJobs().jobs().getFirst().runMetadata());
            MockMvcBuilders.standaloneSetup(controller).build()
                    .perform(get("/experiments/jobs/{jobId}", jobId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jobId").value(jobId))
                    .andExpect(jsonPath("$.runMetadata.runId").value(jobId))
                    .andExpect(jsonPath("$.runMetadata.environment.javaVersion")
                            .value(System.getProperty("java.version")));
            assertEquals("concurrency.queue-contention", state.getExperimentType());
            assertEquals("api-small", state.getScenarioId());
            assertEquals("2", state.getRunMetadata().parameters().get("queueCapacity"));
            assertEquals(40L, state.getSummary().counters().get("items.produced"));
            assertEquals(40L, state.getSummary().counters().get("items.consumed"));
        }
    }

    @Test
    void runsIoEndpointComparisonExperimentAndStoresSummary() throws Exception {
        try (var store = new JdbcRunStore(root, 500, 100);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ExperimentJobService service = new ExperimentJobService(
                    new DurableExperimentRunner(store, () -> GitProvenance.unknown("test"), properties().writerOptions(), 200, 8),
                    store, properties(),
                    new QueueContentionExperiment(),
                    new IoEndpointComparisonExperiment(executor),
                    new SyncTaskExecutor());

            String jobId = service.startIoEndpointComparisonJob(new IoEndpointComparisonScenario(
                    "io-small",
                    3,
                    1L,
                    false,
                    1L));

            ExperimentJobState state = service.getJob(jobId);

            assertNotNull(state);
            assertEquals(jobId, state.getRunMetadata().runId());
            ExperimentController controller = new ExperimentController(service);
            assertEquals(state.getRunMetadata(), controller.getJob(jobId).runMetadata());
            assertEquals(state.getRunMetadata(), controller.listJobs().jobs().getFirst().runMetadata());
            MockMvcBuilders.standaloneSetup(controller).build()
                    .perform(get("/experiments/jobs/{jobId}", jobId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jobId").value(jobId))
                    .andExpect(jsonPath("$.runMetadata.runId").value(jobId))
                    .andExpect(jsonPath("$.runMetadata.environment.javaVersion")
                            .value(System.getProperty("java.version")));
            assertEquals("io.endpoint-comparison", state.getExperimentType());
            assertEquals("io-small", state.getScenarioId());
            assertEquals("3", state.getRunMetadata().parameters().get("requests"));
            assertEquals(3L, state.getSummary().counters().get("requests.virtualCompleted"));
            assertEquals(3L, state.getSummary().counters().get("requests.reactiveCompleted"));
        }
    }

    @Test
    void recordsRejectedSubmissionAsFailedWithMetadata() {
        try (var store = new JdbcRunStore(root, 500, 100);
             var io = Executors.newVirtualThreadPerTaskExecutor();
             var runner = new DurableExperimentRunner(store, () -> GitProvenance.unknown("test"), properties().writerOptions(), 200, 8)) {
            var service = new ExperimentJobService(runner, store, properties(), new QueueContentionExperiment(),
                    new IoEndpointComparisonExperiment(io), task -> {
                        throw new org.springframework.core.task.TaskRejectedException("full");
                    });
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> service.startQueueContentionJob(new QueueContentionScenario("rejected", 1, 1, 1, 1, 0)));
            var run = store.list(null, Long.MAX_VALUE, 1).getFirst();
            assertEquals(com.pnomeer.lab.core.storage.RunStatus.FAILED, run.status());
            assertEquals("rejected", run.scenarioId());
            assertEquals("1", run.runMetadata().parameters().get("queueCapacity"));
        }
    }
}
