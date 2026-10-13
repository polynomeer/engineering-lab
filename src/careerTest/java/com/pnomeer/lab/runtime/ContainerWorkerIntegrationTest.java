package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.*;
import com.pnomeer.lab.worker.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ContainerWorkerIntegrationTest {
    @TempDir static Path imageContext;
    @TempDir Path root;
    static String image, hash;
    static final String tag = "engineering-lab-worker-test:" + UUID.randomUUID();
    private final Experiment<ExperimentScenario> experiment = new Experiment<>() {
        public String type() { return "fixture.sequence"; }
        public ExperimentSummary execute(ExperimentScenario scenario, Consumer<MetricPoint> points) {
            throw new AssertionError("Container execution called the controller experiment body");
        }
    };
    @BeforeAll static void buildImage() throws Exception {
        // Resolve a cached base to its immutable ID. No pulls and no changes to the base image.
        String base = docker("image", "inspect", "eclipse-temurin:21-jre", "--format", "{{.Id}}").trim();
        Path jar = Path.of(System.getProperty("lab.worker.fixture"));
        hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        Files.copy(jar, imageContext.resolve("worker.jar"));
        docker("image", "tag", base, tag + "-base");
        Files.writeString(imageContext.resolve(".dockerignore"), "*\n!Dockerfile\n!worker.jar\n");
        Files.writeString(imageContext.resolve("Dockerfile"), "FROM " + tag + "-base\nCOPY worker.jar /opt/lab/worker.jar\n");
        docker("build", "--network=none", "--pull=false", "-t", tag, imageContext.toString());
        image = docker("image", "inspect", tag, "--format", "{{.Id}}").trim();
    }
    @AfterAll static void removeImage() throws Exception {
        try { removeTag(tag); }
        finally { removeTag(tag + "-base"); }
    }
    private static void removeTag(String reference) throws Exception {
        String[] lookup = {"image", "ls", "--filter", "reference=" + reference, "--format", "{{.Repository}}:{{.Tag}}"};
        if (docker(lookup).isBlank()) return;
        Process removal = new ProcessBuilder("docker", "image", "rm", reference)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try { removal.waitFor(20, TimeUnit.SECONDS); }
        finally {
            if (removal.isAlive()) { removal.destroyForcibly(); assertTrue(removal.waitFor(5, TimeUnit.SECONDS)); }
        }
        // Deletion may have completed before the CLI response. Confirm the exact tag is absent;
        // never repeat mutation or interpret a failed lookup as successful removal.
        assertTrue(docker(lookup).isBlank(), "Test image tag removal unconfirmed: " + reference);
    }
    private DurableExperimentRunner runner(RunStore store, String mode) throws Exception {
        var manifest = new ContainerWorkerManifest(image, hash, "fixture-module", Set.of("fixture.sequence"));
        var limits = new WorkerProcess.Limits(Duration.ofSeconds(mode.equals("hang") ? 8 : 30), Duration.ofSeconds(3), 1024, ProtocolLimits.DEFAULT);
        var executor = new ContainerWorkerExecutor(manifest, limits, Files.createDirectories(root.resolve("workspaces")), root.resolve("db"),
                Set.of("fixture.completed"), ignored -> new LocalJvmExecutor.Request(WireJson.object().put("mode", mode), "a".repeat(64), 1));
        var executors = new ExperimentExecutors("CONTAINER", Map.of("CONTAINER", new ExperimentExecutors.Registration(executor, 1)), Map.of());
        return new DurableExperimentRunner(store, () -> GitProvenance.unknown("controller"), new MetricWriterOptions(8, 4, 20, 2, 1, 2000), executors, 5, 2,
                Map.of("fixture.sequence", new RunExecution.Definition(RunExecution.Kind.CONTAINER, "fixture-module", 1, RunExecution.MeasurementScope.WORKER_PROCESS)));
    }
    @ParameterizedTest @ValueSource(strings={"evidence", "report", "stderr", "descendant-success"})
    void successfulEvidenceSurvivesReopenOnlyAfterContainerRemoval(String mode) throws Exception {
        try (var store = new JdbcRunStore(root.resolve("db"), 20, 4); var runner = runner(store, mode)) {
            assertEquals(ExperimentStatus.SUCCEEDED, runner.run(experiment, () -> "container", "run").status(),
                    () -> store.find("run").orElseThrow().failureMessage());
            assertRemoved();
            var execution = store.execution("run").orElseThrow();
            assertTrue(execution.evidenceComplete()); assertEquals(RunExecution.Kind.CONTAINER, execution.definition().kind());
            assertTrue(execution.worker().environment().javaVersion().startsWith("21"));
            assertTrue(store.find("run").orElseThrow().runMetadata().environment().javaVersion().startsWith("25"));
            if (mode.equals("stderr")) {
                var counters = store.find("run").orElseThrow().summary().counters();
                assertTrue(counters.get("worker.stderr.bytes") >= 2 * 1024 * 1024);
                assertEquals(1024L, counters.get("worker.stderr.retainedBytes"));
            }
        }
        try (var store = new JdbcRunStore(root.resolve("db"), 20, 4)) {
            assertTrue(store.execution("run").orElseThrow().evidenceComplete());
            if (mode.equals("evidence")) {
                assertEquals(1, store.metrics("run", -1, 20).size()); assertEquals(1, store.checks("run", -1, 20).size());
            }
            if (mode.equals("report")) {
                var report = store.artifacts("run").getFirst();
                assertEquals("{\"count\":2}", Files.readString(root.resolve("db").resolve(report.relativePath())));
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"evidence-failed", "bad-report", "malformed", "nonzero", "hang"})
    void failuresDoNotSealAndStillRemoveContainer(String mode) throws Exception {
        try (var store = new JdbcRunStore(root.resolve("db"), 20, 4); var runner = runner(store, mode)) {
            assertEquals(ExperimentStatus.FAILED, runner.run(experiment, () -> "container", "run").status());
            assertFalse(store.execution("run").orElseThrow().evidenceComplete());
            assertRemoved();
            if (mode.equals("evidence-failed")) {
                assertEquals(1, store.metrics("run", -1, 20).size()); assertEquals(1, store.checks("run", -1, 20).size());
            }
            if (mode.equals("bad-report")) assertTrue(store.artifacts("run").isEmpty());
            if (mode.equals("hang")) assertEquals("DEADLINE_EXCEEDED", store.find("run").orElseThrow().failureMessage());
        }
    }
    @Test void interruptionDuringEvidencePersistenceStillRemovesBoundary() throws Exception {
        var manifest = new ContainerWorkerManifest(image, hash, "fixture-module", Set.of("fixture.sequence"));
        var transport = new ContainerWorkerProcess(manifest, new WorkerProcess.Limits(Duration.ofSeconds(30), Duration.ofSeconds(3), 1024, ProtocolLimits.DEFAULT));
        Path workspaces = Files.createDirectories(root.resolve("workspaces"));
        var command = WireJson.object().put("protocolVersion", 1).put("runId", "run").put("experimentId", "fixture.sequence")
                .put("definitionSha256", "a".repeat(64)).put("deadlineEpochMs", System.currentTimeMillis() + 30000)
                .put("seed", 1).put("workspace", workspaces.toString());
        command.set("parameters", WireJson.object().put("mode", "evidence"));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var block = new java.util.concurrent.CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread controller = new Thread(() -> {
            try {
                transport.execute(command, workspaces, new WorkerProcess.EvidenceSink() {
                    public void persist(WorkerProtocol.Event event, Path workspace) throws Exception { entered.countDown(); block.await(); }
                    public void flush() { }
                });
                result.set(new AssertionError("Interrupted transport returned success"));
            } catch (Throwable failure) { result.set(failure); }
        });
        controller.start();
        try {
            assertTrue(entered.await(20, TimeUnit.SECONDS));
            controller.interrupt(); controller.join(15000);
            assertFalse(controller.isAlive()); assertInstanceOf(InterruptedException.class, result.get()); assertRemoved();
        } finally { controller.interrupt(); block.countDown(); controller.join(15000); assertFalse(controller.isAlive()); }
    }
    private void assertRemoved() throws Exception {
        try (var workspaces = Files.list(root.resolve("workspaces"))) {
            var dirs = workspaces.toList(); assertEquals(1, dirs.size());
            Path journal = dirs.getFirst().resolve("container-lifecycle");
            assertTrue(Files.isRegularFile(journal.resolve("removed.json")));
            var created = WireJson.parse(Files.readAllBytes(journal.resolve("created.json")));
            assertTrue(new DockerContainerEngine().inspect(created.path("id").asText()).isEmpty());
        }
    }
    // A failing assertion must not leave a confirmed test-owned boundary running.
    @AfterEach void cleanup() throws Exception {
        Path workspaces = root.resolve("workspaces"); if (!Files.isDirectory(workspaces)) return;
        try (var dirs = Files.list(workspaces)) {
            for (Path workspace : dirs.toList()) {
                var spec = new WorkerContainerLifecycle.Spec("run", hash, image, List.of("sleep", "900"));
                try (var lifecycle = new WorkerContainerLifecycle(workspace.resolve("container-lifecycle"), spec, new DockerContainerEngine())) {
                    assertNotEquals(WorkerContainerLifecycle.Recovery.REQUIRES_OPERATOR, lifecycle.reconcile());
                }
            }
        }
    }
    private static String docker(String... args) throws Exception {
        var command = new ArrayList<>(List.of("docker")); command.addAll(List.of(args));
        Path log = Files.createTempFile(imageContext, "docker-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Docker command timed out");
            String output = Files.readString(log); assertEquals(0, process.exitValue(), output); return output;
        } finally { if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); } }
    }
}
