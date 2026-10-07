package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExperimentExecutorTest {
    @TempDir Path root;
    private static final ExperimentScenario SCENARIO = () -> "boundary";
    private static final MetricPoint POINT = new MetricPoint(0, Map.of(), Map.of(), Map.of());

    @Test
    void stopsObservationAndRegistersJfrBeforeDrainThenLogsStorageFailure() throws Exception {
        var events = new ArrayList<String>();
        var jfr = mock(JfrRecorder.class);
        var recording = mock(JfrRecorder.Session.class);
        var capture = new ArtifactReference("jfr", "order", "jfr/test.jfr", "application/octet-stream", 0, "0".repeat(64));
        when(jfr.start("order")).thenReturn(recording);
        when(recording.finish()).thenAnswer(invocation -> { events.add("jfr-finish"); return capture; });
        doAnswer(invocation -> { events.add("jfr-close"); return null; }).when(recording).close();
        when(recording.counters()).thenReturn(Map.of());
        var executor = new InProcessExecutor(() -> POINT, 0, 10, true, 10, jfr,
                new RunLogRecorder(root, new RunLogProperties(true, 20, 8192)), 2000);
        var artifacts = new ArrayList<ArtifactReference>();
        var outcome = executor.execute(new Experiment<ExperimentScenario>() {
            public String type() { return "test.order"; }
            public ExperimentSummary execute(ExperimentScenario scenario, Consumer<MetricPoint> sink) {
                events.add("execute"); sink.accept(POINT); return ExperimentSummary.empty();
            }
        }, SCENARIO, new ExperimentExecutor.Output("order", point -> events.add("observation"),
                point -> events.add("metric"), new ExecutionEvidence("order", row -> fail("Unexpected dead letter")),
                artifact -> { artifacts.add(artifact); events.add(artifact == capture ? "jfr-register" : "log-register"); },
                () -> { events.add("drain"); throw new IllegalStateException("disk failed"); }));
        assertEquals(List.of("observation", "execute", "metric", "observation", "observation",
                "jfr-finish", "jfr-register", "jfr-close", "drain", "log-register"), events);
        assertEquals(RunStatus.FAILED, outcome.status());
        assertTrue(outcome.failure().contains("Observation storage failed"));
        String log = Files.readString(root.resolve(artifacts.getLast().relativePath()));
        assertTrue(log.contains("STORAGE_FAILED"));
        assertTrue(log.contains("EVIDENCE_FINISHED"));
        assertEquals(1L, outcome.summary().counters().get("trace.spans.recorded"));
    }

    @Test
    void runnerOwnsLifecycleAndUsesExecutorEnvironmentWithoutCapturingItsOwnJvm() {
        var environment = new RunEnvironment("execution-java", "vendor", "vm", "os", "1", "arch", 1, 1024);
        try (var store = new JdbcRunStore(root, 20, 4)) {
            var executor = new ExperimentExecutor() {
                public Map<String, String> parameters() { return Map.of("observation.jvm.mode", "OFF"); }
                public RunEnvironment environment() { return environment; }
                public <C extends ExperimentScenario> Outcome execute(Experiment<C> experiment, C scenario, Output output) {
                    assertEquals(RunStatus.RUNNING, store.find(output.runId()).orElseThrow().status());
                    output.experimentMetrics().accept(POINT);
                    output.drainMetrics().run();
                    return new Outcome(RunStatus.SUCCEEDED, ExperimentSummary.empty(), null, false);
                }
            };
            try (var runner = new DurableExperimentRunner(store, () -> GitProvenance.unknown("test"),
                    new MetricWriterOptions(8, 4, 20, 2, 1, 2000), executor, 5, 2)) {
                var result = runner.run(new Experiment<ExperimentScenario>() {
                    public String type() { return "test.boundary"; }
                    public ExperimentSummary execute(ExperimentScenario scenario, Consumer<MetricPoint> sink) {
                        throw new AssertionError("Only the selected executor may invoke the experiment");
                    }
                }, SCENARIO, "boundary");
                assertEquals(environment, result.runMetadata().environment());
                assertEquals(RunStatus.SUCCEEDED, store.find("boundary").orElseThrow().status());
                assertEquals(1, store.metrics("boundary", -1, 20).size());
                assertEquals(1, result.timeline().size());
                assertEquals(1L, result.summary().counters().get("storage.metrics.written"));
            }
        }
    }
}
