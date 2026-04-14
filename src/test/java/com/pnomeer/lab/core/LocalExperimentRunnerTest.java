package com.pnomeer.lab.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LocalExperimentRunnerTest {
    private final LocalExperimentRunner runner = new LocalExperimentRunner();

    @Test
    void capturesTimelineAndSummaryOnSuccess() {
        Experiment<TestScenario> experiment = new Experiment<>() {
            @Override
            public String type() {
                return "queue-contention";
            }

            @Override
            public ExperimentSummary execute(TestScenario scenario, java.util.function.Consumer<MetricPoint> timelineListener) {
                timelineListener.accept(new MetricPoint(
                        System.currentTimeMillis(),
                        Map.of("throughput.rowsPerSec", 1200.0d),
                        Map.of("rows.produced", 100L),
                        Map.of("mode", "baseline")));
                return new ExperimentSummary(
                        Map.of("rows.produced", 100L),
                        Map.of("throughput.rowsPerSec", 1200.0d),
                        Map.of("scenario", scenario.displayName()));
            }
        };

        ExperimentResult result = runner.run(experiment, new TestScenario("baseline-small"));

        assertEquals(ExperimentStatus.SUCCEEDED, result.status());
        assertEquals("queue-contention", result.experimentType());
        assertEquals("baseline-small", result.scenarioId());
        assertEquals(100L, result.summary().counters().get("rows.produced"));
        assertEquals(1, result.timeline().size());
        assertFalse(result.startedAtEpochMs() > result.completedAtEpochMs());
    }

    @Test
    void returnsFailedResultWhenExperimentThrows() {
        Experiment<TestScenario> experiment = new Experiment<>() {
            @Override
            public String type() {
                return "db-lock-test";
            }

            @Override
            public ExperimentSummary execute(TestScenario scenario, java.util.function.Consumer<MetricPoint> timelineListener)
                    throws Exception {
                timelineListener.accept(new MetricPoint(
                        System.currentTimeMillis(),
                        Map.of("lock.waitMs", 55.0d),
                        Map.of(),
                        Map.of()));
                throw new IllegalStateException("deadlock detected");
            }
        };

        ExperimentResult result = runner.run(experiment, new TestScenario("contention"));

        assertEquals(ExperimentStatus.FAILED, result.status());
        assertEquals("deadlock detected", result.failureMessage());
        assertEquals(1, result.timeline().size());
        assertEquals(0, result.summary().counters().size());
    }

    private record TestScenario(String scenarioId) implements ExperimentScenario {
    }
}
