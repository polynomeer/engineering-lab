package com.pnomeer.lab.runtime;

import com.pnomeer.pipeline.ExcelPipelineApplication;
import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.experiments.career.SequenceRegistrationConfiguration;
import com.pnomeer.lab.scenario.ScenarioDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class SequenceStartupRecoveryTest {
    @TempDir Path root;
    private ConfigurableApplicationContext start(boolean recovery) {
        return new SpringApplicationBuilder(ExcelPipelineApplication.class).web(WebApplicationType.NONE)
                .run("--lab.storage.root=" + root, "--spring.main.banner-mode=off", "--logging.level.root=WARN",
                        "--lab.career.sequence.enabled=true", "--lab.career.sequence.recover-on-startup=" + recovery,
                        "--lab.career.sequence.manifest=" + System.getProperty("lab.sequence.manifest"),
                        "--lab.career.sequence.java-executable=" + System.getProperty("lab.worker.java"));
    }
    private void run(ConfigurableApplicationContext app, String id) {
        var definition = new ScenarioDefinition(1, SequenceRegistrationConfiguration.ID,
                new ScenarioDefinition.Scenario("restart", "Atomic allocation preserves coverage"),
                Map.of("variant", "ATOMIC_UPDATE", "workers", 2, "blockSize", 10),
                new ScenarioDefinition.Measurement("SINGLE_PASS"), null);
        var scenario = new SequenceRegistrationConfiguration.Scenario(definition, "a".repeat(64));
        var experiment = new Experiment<SequenceRegistrationConfiguration.Scenario>() {
            public String type() { return SequenceRegistrationConfiguration.ID; }
            public ExperimentSummary execute(SequenceRegistrationConfiguration.Scenario s, Consumer<MetricPoint> out) {
                throw new AssertionError("Expected external worker");
            }
        };
        assertTimeoutPreemptively(Duration.ofMinutes(5), () -> assertEquals(ExperimentStatus.SUCCEEDED,
                app.getBean(DurableExperimentRunner.class).run(experiment, scenario, id).status()));
    }
    @Test void actualDeploymentReconcilesCompletedBoundaryOnOptInRestartAndAdmitsNextRun() throws Exception {
        Path workspace; String hash; StoredRun original;
        try (var app = start(false)) {
            run(app, "before-restart");
            var binding = app.getBean(LocalJvmRegistration.class);
            original = app.getBean(RunStore.class).find("before-restart").orElseThrow();
            workspace = binding.workspaces().resolve(original.summary().details().get("worker.workspace"));
            hash = binding.manifest().artifactSha256();
        }
        // Recreate precisely the durable boundary after transport completion and before admission deletion.
        // This is a crash-window fixture, not a Docker daemon/host crash experiment.
        var guard = new WorkerRecoveryGuard(LocalJvmRegistration.marker(root.resolve("worker-workspaces"), "sequence-worker"));
        guard.begin("before-restart", hash).recordCompletion(workspace, "SUCCEEDED", "");
        try (var app = start(false)) {
            assertEquals("WORKER_ACTIVE_OR_RECOVERY_REQUIRED", app.getBean(LocalJvmRegistration.class).readiness().get().reason());
            assertTrue(guard.pending());
            assertThrows(Exception.class, () -> { try (var second = start(true)) { /* Owner lock must reject before recovery. */ } });
            assertTrue(guard.pending());
        }
        try (var app = start(true)) {
            assertFalse(guard.pending());
            assertTrue(app.getBean(LocalJvmRegistration.class).readiness().get().ready());
            assertEquals(original, app.getBean(RunStore.class).find("before-restart").orElseThrow());
            run(app, "after-restart");
        }
        guard.begin("unconfirmed-crash", hash);
        try (var app = start(true)) {
            assertTrue(guard.pending());
            assertEquals("WORKER_ACTIVE_OR_RECOVERY_REQUIRED", app.getBean(LocalJvmRegistration.class).readiness().get().reason());
        }
    }
}
