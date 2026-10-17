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
    @Test
    @org.junit.jupiter.api.Timeout(5)
    void samplesWhileWorkersRunWithMonotonicTimeAndSerializedCallbacks() throws Exception {
        var samples = new java.util.ArrayList<com.pnomeer.lab.core.MetricPoint>();
        Thread coordinator = Thread.currentThread();
        var summary = new QueueContentionExperiment(20, 1000).execute(
                new QueueContentionScenario("live", 2, 1, 1, 80, 5L), point -> {
                    assertEquals(coordinator, Thread.currentThread());
                    samples.add(point);
                });
        assertTrue(samples.size() > 5);
        assertTrue(samples.stream().anyMatch(p -> p.counters().get("items.consumed") > 0
                && p.counters().get("items.consumed") < 80));
        long previous = -1;
        for (var point : samples) {
            long elapsed = point.counters().get("time.elapsedMs");
            assertTrue(elapsed >= previous);
            assertTrue(point.gauges().get("queue.depth") <= 2);
            assertEquals("20", point.labels().get("metric.interval.ms"));
            previous = elapsed;
        }
        assertEquals(80L, samples.getLast().counters().get("items.consumed"));
        assertEquals("20", summary.details().get("metricIntervalMs"));
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void listenerFailureStopsBlockedWorkers() {
        var callbacks = new java.util.concurrent.atomic.AtomicInteger();
        var failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                new QueueContentionExperiment(20, 1000).execute(
                        new QueueContentionScenario("failure", 1, 1, 1, 1000, 10L), point -> {
                            if (callbacks.incrementAndGet() == 2) throw new IllegalStateException("storage unavailable");
                        }));
        assertEquals("storage unavailable", failure.getMessage());
        assertEquals(2, callbacks.get());
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive()
                && (t.getName().startsWith("lab-queue-producer-") || t.getName().startsWith("lab-queue-consumer-"))));
    }
}
