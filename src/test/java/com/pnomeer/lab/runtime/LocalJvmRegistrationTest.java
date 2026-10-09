package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.*;
import com.pnomeer.lab.worker.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class LocalJvmRegistrationTest {
    @TempDir Path root;
    private WorkerManifest manifest() throws Exception {
        Path jar = Path.of(System.getProperty("lab.worker.fixture"));
        String hash;
        try (var in = Files.newInputStream(jar)) { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())); }
        return new WorkerManifest(Path.of(System.getProperty("lab.worker.java")), jar, hash, "fixture-module", Set.of("fixture.sequence"), 64);
    }
    private LocalJvmRegistration registration(boolean ready) throws Exception {
        return new LocalJvmRegistration("fixture-worker", manifest(), Files.createDirectories(root.resolve("workspaces")), root,
                Set.of("fixture.completed"), ignored -> new LocalJvmExecutor.Request(WireJson.object().put("mode", "evidence"), "a".repeat(64), 1),
                () -> new LocalJvmRegistration.Readiness(ready ? "READY" : "DOCKER_UNAVAILABLE", null), List.of("BEFORE", "AFTER"));
    }
    private DurableExperimentRunner runner(RunStore store, LocalJvmRegistration binding) {
        return DurableExperimentRunner.configured(store, () -> GitProvenance.unknown("test"),
                new MetricWriterOptions(8,4,20,2,1,2000), 5, 2, null, 0, 10, false, 10, null, null,
                Map.of("fixture.sequence", "fixture-module"), List.of(binding));
    }
    private final Experiment<ExperimentScenario> experiment = new Experiment<>() {
        public String type() { return "fixture.sequence"; }
        public ExperimentSummary execute(ExperimentScenario s, Consumer<MetricPoint> sink) { throw new AssertionError("No in-process fallback"); }
    };
    @Test void unavailableWorkerRejectsBeforeCreatingRun() throws Exception {
        try (var store = new JdbcRunStore(root.resolve("db"), 20, 4); var runner = runner(store, registration(false))) {
            assertThrows(LocalJvmRegistration.WorkerUnavailableException.class, () -> runner.run(experiment, () -> "scenario", "rejected"));
            assertTrue(store.find("rejected").isEmpty());
        }
    }
    @Test void registeredWorkerUsesCentralEvidenceAndPreservesWorkerIdentity() throws Exception {
        try (var store = new JdbcRunStore(root.resolve("db"), 20, 4); var runner = runner(store, registration(true))) {
            assertEquals(ExperimentStatus.SUCCEEDED, runner.run(experiment, () -> "scenario", "routed").status());
            var execution = store.execution("routed").orElseThrow();
            assertEquals(RunExecution.Kind.LOCAL_JVM, execution.definition().kind());
            assertTrue(execution.evidenceComplete());
            assertTrue(execution.worker().environment().javaVersion().startsWith("21"));
            assertEquals(1, store.checks("routed", -1, 20).size());
        }
    }
    @Test void readinessVerifiesJarAndSameJavaDeploymentProbe() throws Exception {
        var manifest = manifest();
        assertEquals("READY", new WorkerReadiness(manifest, Duration.ofSeconds(10)).get().state());
        var missing = new WorkerManifest(manifest.javaExecutable(), root.resolve("missing.jar"), manifest.artifactSha256(),
                manifest.moduleId(), manifest.experiments(), 64);
        assertEquals("NOT_BUILT", new WorkerReadiness(missing, Duration.ofSeconds(10)).get().state());
        var changed = new WorkerManifest(manifest.javaExecutable(), manifest.jar(), "a".repeat(64), manifest.moduleId(), manifest.experiments(), 64);
        assertEquals("INCOMPATIBLE", new WorkerReadiness(changed, Duration.ofSeconds(10)).get().state());
        var wrongIdentity = new WorkerManifest(manifest.javaExecutable(), manifest.jar(), manifest.artifactSha256(), "other", manifest.experiments(), 64);
        assertEquals("INCOMPATIBLE", new WorkerReadiness(wrongIdentity, Duration.ofSeconds(10)).get().state());
    }
    @Test void bindingMustMatchCatalogModuleOwnership() throws Exception {
        var binding = registration(true);
        try (var store = new JdbcRunStore(root.resolve("db"), 20, 4)) {
            assertThrows(IllegalArgumentException.class, () -> DurableExperimentRunner.configured(store, () -> GitProvenance.unknown("test"),
                    new MetricWriterOptions(8,4,20,2,1,2000), 5, 2, null, 0, 10, false, 10, null, null, Map.of(), List.of(binding)));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"unavailable,DOCKER_UNAVAILABLE", "oversize,INCOMPATIBLE", "hang,DOCKER_UNAVAILABLE"})
    void boundedProbeRejectsUnavailableOversizeAndUnresponsiveWorker(String mode, String state) throws Exception {
        var original = manifest();
        Path jar = root.resolve("probe.jar");
        try (var source = new java.util.zip.ZipInputStream(Files.newInputStream(original.jar()));
             var target = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            java.util.zip.ZipEntry entry;
            while ((entry = source.getNextEntry()) != null) {
                target.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                source.transferTo(target); target.closeEntry();
            }
            target.putNextEntry(new java.util.zip.ZipEntry("probe-mode.txt"));
            target.write(mode.getBytes(java.nio.charset.StandardCharsets.UTF_8)); target.closeEntry();
        }
        String hash;
        try (var input = Files.newInputStream(jar)) {
            var digest = MessageDigest.getInstance("SHA-256"); byte[] block = new byte[8192]; int n;
            while ((n = input.read(block)) != -1) digest.update(block,0,n);
            hash = HexFormat.of().formatHex(digest.digest());
        }
        var probe = new WorkerReadiness(new WorkerManifest(original.javaExecutable(), jar, hash, original.moduleId(), original.experiments(), 64), Duration.ofSeconds(2));
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertEquals(state, probe.get().state()));
    }
}
