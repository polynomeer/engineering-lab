package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.JdbcRunStore;
import com.pnomeer.lab.worker.WireJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real daemon crash fixture. Not executed by the ordinary integration suite. */
class SequenceDaemonRecoveryTest {
    private static final String ENGINE_IMAGE = "docker:27.5.1-dind";
    private static final String OWNER = "engineering-lab.daemon-test", RUN = "fault-run";
    @TempDir Path root;

    @Test void privateDaemonCrashPreservesAdmissionAndRestartReconcilesOnlyOwnedContainer() throws Exception {
        assertTrue(Boolean.getBoolean("lab.daemon.privileged"), "Explicit privileged daemon test consent is required");
        String token = UUID.randomUUID().toString(), container = null;
        System.out.println("stage=private_daemon_test owner_token=" + token);
        try {
            // Never download images implicitly or mount the host Docker socket/data into this engine.
            ok("docker", "image", "inspect", ENGINE_IMAGE, "--format", "{{.Id}}");
            long size = Long.parseLong(ok("docker", "image", "inspect", "busybox:1.37", "--format", "{{.Size}}").trim());
            assertTrue(size < 16 * 1024 * 1024, "Fixture image unexpectedly large");
            container = ok("docker", "create", "--pull=never", "--privileged", "--memory=1g", "--cpus=1", "--pids-limit=256",
                    "--label", OWNER + "=" + token, "--publish", "127.0.0.1::2375", "--env", "DOCKER_TLS_CERTDIR=",
                    ENGINE_IMAGE, "--tls=false", "--storage-driver=vfs").trim();
            assertTrue(container.matches("[a-f0-9]{64}"));
            System.out.println("stage=private_daemon_created container_id=" + container + " owner_token=" + token);
            verifyOwner(container, token);
            ok("docker", "start", container);
            String engineId = awaitEngine(container), address = address(container);
            Path image = root.resolve("fixture-image.tar");
            ok("docker", "image", "save", "--output", image.toString(), "busybox:1.37");
            assertTrue(Files.size(image) < 32 * 1024 * 1024);
            ok("docker", "cp", image.toString(), container + ":/tmp/fixture-image.tar");
            inner(container, "image", "load", "--input", "/tmp/fixture-image.tar");
            // Only engine metadata/recovery is tested. This sleeping fixture is not a MySQL workload.
            inner(container, "image", "tag", "busybox:1.37", "mysql:8.0.39");
            var descriptor = WireJson.parse(Files.readAllBytes(Path.of(System.getProperty("lab.sequence.manifest"))));
            String hash = descriptor.path("artifactSha256").asText();
            String owned = inner(container, "run", "-d", "--name", "engineering-lab-sequence-" + token,
                    "--label", "engineering-lab.run-id=" + RUN, "--label", "engineering-lab.owner-token=" + token,
                    "--label", "engineering-lab.artifact-sha256=" + hash,
                    "--label", "engineering-lab.resource=sequence-mysql-v1", "mysql:8.0.39", "sleep", "600").trim();
            String sentinel = inner(container, "run", "-d", "--label", "engineering-lab.run-id=" + RUN,
                    "--label", "engineering-lab.owner-token=" + UUID.randomUUID(), "busybox:1.37", "sleep", "600").trim();
            assertTrue(owned.matches("[a-f0-9]{64}")); assertTrue(sentinel.matches("[a-f0-9]{64}"));
            Path workspace = Files.createDirectories(root.resolve("worker-workspaces/worker-daemon"));
            var journal = new Properties();
            journal.setProperty("version", "1"); journal.setProperty("runId", RUN); journal.setProperty("artifactSha256", hash);
            journal.setProperty("daemonId", engineId); journal.setProperty("token", token);
            Path journalFile = workspace.resolve("sequence-container.properties");
            try (var output = Files.newOutputStream(journalFile)) { journal.store(output, "Real private daemon fixture"); }
            byte[] journalBefore = Files.readAllBytes(journalFile);
            Path marker = LocalJvmRegistration.marker(workspace.getParent(), "sequence-worker");
            var guard = new WorkerRecoveryGuard(marker);
            guard.begin(RUN, hash).recordCompletion(workspace, "FAILED", "EXPECTATION_NOT_MET");
            Path receipt = marker.resolveSibling(marker.getFileName() + ".completed");
            byte[] markerBefore = Files.readAllBytes(marker), receiptBefore = Files.readAllBytes(receipt);
            StoredRun original;
            try (var store = new JdbcRunStore(root, 20, 4)) {
                store.create("career.mcp-platform.sequence", "daemon", new RunMetadata(RUN, Map.of(), RunEnvironment.capture()));
                store.start(RUN); store.complete(RUN, RunStatus.FAILED, ExperimentSummary.empty(), "Original failure");
                original = store.find(RUN).orElseThrow();
            }
            verifyOwner(container, token);
            ok("docker", "kill", "--signal=KILL", container);
            assertEquals("false", ok("docker", "inspect", "--format", "{{.State.Running}}", container).trim());
            assertEquals("FAILED", recover(address));
            assertTrue(guard.pending());
            assertArrayEquals(markerBefore, Files.readAllBytes(marker));
            assertArrayEquals(receiptBefore, Files.readAllBytes(receipt));
            verifyOwner(container, token);
            ok("docker", "start", container);
            assertEquals(engineId, awaitEngine(container), "Restart must retain engine data/identity");
            address = address(container); // An automatically allocated host port may change after restart.
            assertEquals(owned, inner(container, "inspect", "--format", "{{.Id}}", owned).trim());
            assertEquals(sentinel, inner(container, "inspect", "--format", "{{.Id}}", sentinel).trim());
            assertEquals("RECOVERED", recover(address));
            assertFalse(guard.pending());
            assertEquals("", inner(container, "ps", "-aq", "--filter", "id=" + owned).trim());
            assertEquals(sentinel, inner(container, "inspect", "--format", "{{.Id}}", sentinel).trim());
            assertEquals("NOT_REQUIRED", recover(address));
            assertArrayEquals(journalBefore, Files.readAllBytes(journalFile));
            try (var store = new JdbcRunStore(root, 20, 4)) { assertEquals(original, store.find(RUN).orElseThrow()); }
        } finally {
            if (container != null) {
                verifyOwner(container, token);
                ok("docker", "rm", "--force", "--volumes", container);
            }
        }
    }
    private String recover(String address) throws Exception {
        Path result = root.resolve("attempt-result"); Files.deleteIfExists(result);
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m",
                "-cp", System.getProperty("lab.controller.classpath"), SequenceRecoveryTransportTest.Controller.class.getName(),
                root.toString(), System.getProperty("lab.sequence.manifest"), System.getProperty("lab.worker.java"))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "DOCKER_CONTEXT",
                "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH")) builder.environment().remove(key);
        builder.environment().put("DOCKER_HOST", "tcp://" + address);
        builder.environment().put("TESTCONTAINERS_RYUK_DISABLED", "true");
        builder.environment().put("TESTCONTAINERS_CHECKS_DISABLE", "true");
        Process child = builder.start();
        try {
            assertTrue(child.waitFor(80, TimeUnit.SECONDS), "Recovery exceeded deadline");
            assertEquals(0, child.exitValue()); return Files.readString(result);
        } finally { if (child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); } }
    }
    private static String address(String container) throws Exception {
        String address = ok("docker", "port", container, "2375/tcp").trim();
        assertTrue(address.matches("127\\.0\\.0\\.1:[0-9]+")); return address;
    }
    private static void verifyOwner(String container, String token) throws Exception {
        assertTrue(container.matches("[a-f0-9]{64}"));
        assertEquals(token, ok("docker", "inspect", "--format", "{{index .Config.Labels \"" + OWNER + "\"}}", container).trim());
    }
    private static String awaitEngine(String container) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            var result = command("docker", "exec", container, "docker", "--host", "unix:///var/run/docker.sock", "info", "--format", "{{.ID}}");
            if (result.code == 0 && !result.output.isBlank()) return result.output.trim();
            Thread.sleep(500);
        }
        throw new AssertionError("Private daemon did not become ready");
    }
    private static String inner(String container, String... args) throws Exception {
        var command = new ArrayList<>(List.of("docker", "exec", container, "docker", "--host", "unix:///var/run/docker.sock"));
        command.addAll(List.of(args)); return ok(command.toArray(String[]::new));
    }
    private record Command(int code, String output) {}
    private static String ok(String... args) throws Exception {
        var result = command(args); assertEquals(0, result.code, result.output); return result.output;
    }
    private static Command command(String... args) throws Exception {
        Process child = new ProcessBuilder(args).redirectErrorStream(true).start();
        var io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "daemon-test-command"); t.setDaemon(true); return t; });
        try {
            var output = io.submit(() -> child.getInputStream().readNBytes(16385));
            byte[] bytes = output.get(30, TimeUnit.SECONDS);
            assertTrue(bytes.length <= 16384, "Docker CLI output exceeded limit");
            assertTrue(child.waitFor(2, TimeUnit.SECONDS), "Docker CLI did not exit");
            return new Command(child.exitValue(), new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); }
            io.shutdownNow(); assertTrue(io.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
