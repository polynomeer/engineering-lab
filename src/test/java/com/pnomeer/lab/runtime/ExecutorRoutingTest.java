package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorRoutingTest {
    @TempDir Path root;
    private final ExperimentScenario scenario = () -> "small";
    private final MetricWriterOptions options = new MetricWriterOptions(8, 4, 20, 2, 1, 2000);

    private Experiment<ExperimentScenario> experiment(String type) {
        return new Experiment<>() {
            public String type() { return type; }
            public Map<String, String> parameters(ExperimentScenario scenario) {
                return Map.of("executor", "isolated"); // Untrusted parameters cannot select routing.
            }
            public ExperimentSummary execute(ExperimentScenario scenario, Consumer<MetricPoint> sink) {
                return ExperimentSummary.empty();
            }
        };
    }

    @Test
    void routesByServerBindingAndDoesNotAttachLocalObservationToAnotherExecutor() {
        var probes = new AtomicInteger();
        var local = new InProcessExecutor(() -> {
            probes.incrementAndGet(); return new MetricPoint(0, Map.of(), Map.of(), Map.of());
        }, 0, 10, false, 10, null, null, 2000);
        var isolated = new ExperimentExecutor() {
            public Map<String, String> parameters() { return Map.of("observation.jvm.mode", "OFF"); }
            public <C extends ExperimentScenario> Outcome execute(Experiment<C> experiment, C scenario, Output output) {
                output.drainMetrics().run();
                return new Outcome(RunStatus.SUCCEEDED, ExperimentSummary.empty(), null, false);
            }
        };
        var executors = new ExperimentExecutors("local", Map.of(
                "local", new ExperimentExecutors.Registration(local, 2),
                "isolated", new ExperimentExecutors.Registration(isolated, 1)),
                Map.of("test.isolated", new ExperimentExecutors.Binding("isolated", true)));
        try (var store = new JdbcRunStore(root, 20, 4);
             var runner = new DurableExperimentRunner(store, () -> GitProvenance.unknown("test"), options, executors, 5, 2)) {
            var isolatedResult = runner.run(experiment("test.isolated"), scenario, "isolated");
            assertEquals(0, probes.get());
            assertTrue(store.metrics("isolated", -1, 20).isEmpty());
            assertEquals("OFF", isolatedResult.runMetadata().parameters().get("observation.jvm.mode"));
            assertEquals(RunEnvironment.capture(), isolatedResult.runMetadata().environment());
            runner.run(experiment("test.local"), scenario, "local");
            assertEquals(2, probes.get());
            assertEquals(2, store.metrics("local", -1, 20).size());
        }
    }

    @Test
    void rejectedRunIsDurableAndDuplicateCannotCorruptActiveOwner() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var held = new Experiment<ExperimentScenario>() {
            public String type() { return "test.held"; }
            public ExperimentSummary execute(ExperimentScenario scenario, Consumer<MetricPoint> sink) throws Exception {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
                return ExperimentSummary.empty();
            }
        };
        try (var store = new JdbcRunStore(root, 20, 4);
             var runner = new DurableExperimentRunner(store, () -> GitProvenance.unknown("test"), options, 5, 1);
             var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var prepared = runner.prepare(held, scenario, "held");
            var running = threads.submit(() -> runner.runPrepared(held, scenario, prepared));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> runner.runPrepared(held, scenario, prepared));
                assertEquals(RunStatus.RUNNING, store.find("held").orElseThrow().status());
                assertThrows(ExecutionAdmission.Rejected.class, () -> runner.run(experiment("test.other"), scenario, "rejected"));
                assertEquals(RunStatus.FAILED, store.find("rejected").orElseThrow().status());
                assertThrows(IllegalArgumentException.class, () -> runner.runPrepared(experiment("test.other"), scenario, prepared));
            } finally { release.countDown(); }
            assertEquals(ExperimentStatus.SUCCEEDED, running.get(2, TimeUnit.SECONDS).status());
            assertEquals(ExperimentStatus.SUCCEEDED, runner.run(experiment("test.other"), scenario, "after").status());
        }
    }
}
