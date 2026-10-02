package com.pnomeer.lab.core;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(System.getProperty("java.version"), result.runMetadata().environment().javaVersion());
        assertTrue(result.runMetadata().environment().availableProcessors() > 0);
        assertTrue(result.runMetadata().environment().maxHeapBytes() > 0);
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

        ExperimentResult result = runner.run(experiment, new TestScenario("contention"), "failed-run");

        assertEquals(ExperimentStatus.FAILED, result.status());
        assertEquals("deadlock detected", result.failureMessage());
        assertEquals("failed-run", result.runMetadata().runId());
        assertEquals(System.getProperty("os.arch"), result.runMetadata().environment().osArch());
        assertEquals(1, result.timeline().size());
        assertEquals(0, result.summary().counters().size());
    }

    @Test
    void snapshotsParametersBeforeExecutionAndKeepsRunIdentitiesSeparate() {
        Map<String, String> parameters = new HashMap<>(Map.of("queueCapacity", "2"));
        ExperimentScenario scenario = new ExperimentScenario() {
            public String scenarioId() { return "mutable"; }
            public Map<String, String> parameters() { return parameters; }
        };
        Experiment<ExperimentScenario> experiment = new Experiment<>() {
            public String type() { return "snapshot-test"; }
            public ExperimentSummary execute(ExperimentScenario input,
                    java.util.function.Consumer<MetricPoint> listener) {
                parameters.put("queueCapacity", "99");
                return ExperimentSummary.empty();
            }
        };

        ExperimentResult first = runner.run(experiment, scenario);
        ExperimentResult second = runner.run(experiment, scenario);

        assertEquals("2", first.runMetadata().parameters().get("queueCapacity"));
        assertEquals("99", second.runMetadata().parameters().get("queueCapacity"));
        UUID.fromString(first.runMetadata().runId());
        assertNotEquals(first.runMetadata().runId(), second.runMetadata().runId());
        assertThrows(UnsupportedOperationException.class,
                () -> first.runMetadata().parameters().put("queueCapacity", "3"));
        assertThrows(IllegalArgumentException.class, () -> runner.run(experiment, scenario, " "));
    }

    private record TestScenario(String scenarioId) implements ExperimentScenario {
    }
}
