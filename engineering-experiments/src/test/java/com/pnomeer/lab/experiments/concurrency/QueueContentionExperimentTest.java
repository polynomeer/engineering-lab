package com.pnomeer.lab.experiments.concurrency;

import com.pnomeer.lab.core.ExperimentResult;
import com.pnomeer.lab.core.ExperimentStatus;
import com.pnomeer.lab.core.LocalExperimentRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueContentionExperimentTest {
    private final LocalExperimentRunner runner = new LocalExperimentRunner();
    private final QueueContentionExperiment experiment = new QueueContentionExperiment();

    @Test
    void runsThroughSharedExperimentRunner() {
        QueueContentionScenario scenario = new QueueContentionScenario(
                "small-contention",
                2,
                2,
                1,
                40,
                2L);

        ExperimentResult result = runner.run(experiment, scenario);

        assertEquals(ExperimentStatus.SUCCEEDED, result.status());
        assertEquals("concurrency.queue-contention", result.experimentType());
        assertEquals(80L, result.summary().counters().get("items.produced"));
        assertEquals(80L, result.summary().counters().get("items.consumed"));
        assertTrue(result.summary().gauges().get("queue.maxDepth") <= 2.0d);
        assertTrue(result.summary().counters().get("blocking.events") > 0L);
        assertTrue(result.timeline().size() >= 2);
    }
}
