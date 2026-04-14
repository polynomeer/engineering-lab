package com.pnomeer.lab.app.experiment;

import com.pnomeer.lab.core.LocalExperimentRunner;
import com.pnomeer.lab.experiments.concurrency.QueueContentionExperiment;
import com.pnomeer.lab.experiments.concurrency.QueueContentionScenario;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ExperimentJobServiceTest {
    @Test
    void runsQueueContentionExperimentAndStoresSummary() {
        ExperimentJobService service = new ExperimentJobService(
                new LocalExperimentRunner(),
                new QueueContentionExperiment(),
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
        assertEquals("concurrency.queue-contention", state.getExperimentType());
        assertEquals("api-small", state.getScenarioId());
        assertEquals(40L, state.getSummary().counters().get("items.produced"));
        assertEquals(40L, state.getSummary().counters().get("items.consumed"));
    }
}
