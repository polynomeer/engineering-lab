package com.pnomeer.lab.app.experiment;

import com.pnomeer.lab.core.LocalExperimentRunner;
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
    @Test
    void runsQueueContentionExperimentAndStoresSummary() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ExperimentJobService service = new ExperimentJobService(
                    new LocalExperimentRunner(),
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
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ExperimentJobService service = new ExperimentJobService(
                    new LocalExperimentRunner(),
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
}
