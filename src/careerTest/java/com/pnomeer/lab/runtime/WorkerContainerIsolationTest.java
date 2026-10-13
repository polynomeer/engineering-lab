package com.pnomeer.lab.runtime;

import com.pnomeer.lab.worker.WireJson;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deployment design proof only: does not enable container execution or release production admission. */
class WorkerContainerIsolationTest {
    private static final String OWNER = "engineering-lab.isolation-test";
    private static final String SCRIPT = """
            cat > /tmp/detached.sh <<'CHILD'
            trap '' TERM
            echo $$ > /tmp/detached.pid
            while :; do sleep 1; done
            CHILD
            sh -c 'setsid sh /tmp/detached.sh </dev/null >/dev/null 2>&1 &'
            while :; do sleep 1; done
            """;
    @TempDir Path root;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void freshControllerReclaimsDetachedProcessesWithoutReleasingUnconfirmedAdmission(boolean killInit) throws Exception {
        String token = UUID.randomUUID().toString(), sentinelToken = UUID.randomUUID().toString();
        String image = ok("docker", "image", "inspect", "busybox:1.37", "--format", "{{.Id}}").trim();
        assertTrue(image.matches("sha256:[a-f0-9]{64}"));
        String engine = ok("docker", "info", "--format", "{{.ID}}").trim();
        assertTrue(engine.matches("[a-zA-Z0-9:-]{1,200}"));
        String owned = null, sentinel = null;
        Process controller = null;
        Path marker = LocalJvmRegistration.marker(root, "isolation-fixture");
        var guard = new WorkerRecoveryGuard(marker);
        guard.begin("isolation-run", "a".repeat(64)); // Intentionally no completion receipt.
        byte[] before = Files.readAllBytes(marker);
        try {
            owned = create(token, image, SCRIPT);
            sentinel = create(sentinelToken, image, "exec sleep 300");
            ok("docker", "start", sentinel);
            verify(owned, token, image, engine);
            controller = controller("start", owned, token, image, engine);
            awaitFile(root.resolve("started"));
            String detached = awaitDetached(owned);
            var fields = detached.split("\\s+");
            assertEquals(3, fields.length);
            assertNotEquals("1", fields[0]);
            assertEquals("1", fields[1], "Detached process must be reparented to namespace init");
            assertEquals(fields[0], fields[2], "Detached process must own a separate session");

            controller.destroyForcibly();
            assertTrue(controller.waitFor(5, TimeUnit.SECONDS));
            assertEquals("true", ok("docker", "inspect", "--format", "{{.State.Running}}", owned).trim());
            assertEquals(detached, detached(owned), "Controller death must leave the detached fixture alive");
            if (killInit) {
                verify(owned, token, image, engine);
                ok("docker", "kill", "--signal=KILL", owned);
                assertEquals("false 0", ok("docker", "inspect", "--format", "{{.State.Running}} {{.State.Pid}}", owned).trim());
            }
            // A new JVM has no ProcessHandle observations from the dead controller.
            Process recovery = controller("recover", owned, token, image, engine);
            try {
                assertTrue(recovery.waitFor(60, TimeUnit.SECONDS));
                assertEquals(0, recovery.exitValue(), "Fresh recovery controller failed");
            } finally {
                if (recovery.isAlive()) { recovery.destroyForcibly(); assertTrue(recovery.waitFor(5, TimeUnit.SECONDS)); }
            }
            assertEquals("", ok("docker", "ps", "-aq", "--filter", "id=" + owned).trim());
            assertEquals("true", ok("docker", "inspect", "--format", "{{.State.Running}}", sentinel).trim());
            assertArrayEquals(before, Files.readAllBytes(marker));
            assertTrue(guard.pending(), "Containment proof alone cannot authorize admission release");
            System.out.println("stage=containment_verified init_killed=" + killInit + " container_id=" + owned);
        } finally {
            if (controller != null && controller.isAlive()) {
                controller.destroyForcibly(); assertTrue(controller.waitFor(5, TimeUnit.SECONDS));
            }
            // Attempt both cleanups even if one fails; never prune shared Docker resources.
            Throwable failure = null;
            for (var entry : List.of(new String[]{owned, token}, new String[]{sentinel, sentinelToken})) {
                if (entry[0] == null) continue;
                try { remove(entry[0], entry[1], image, engine); }
                catch (Throwable e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            if (failure != null) throw new AssertionError("Fixture cleanup failed", failure);
        }
    }

    private static String create(String token, String image, String script) throws Exception {
        String id = ok("docker", "create", "--pull=never", "--label", OWNER + "=" + token,
                "--name", "lab-isolation-" + token, "--user", "65534:65534", "--read-only",
                "--cap-drop=ALL", "--security-opt=no-new-privileges", "--network=none", "--restart=no",
                "--memory=64m", "--cpus=0.5", "--pids-limit=32", "--log-driver=none",
                "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=1m", image, "sh", "-c", script).trim();
        assertTrue(id.matches("[a-f0-9]{64}"));
        System.out.println("stage=containment_created container_id=" + id + " owner_token=" + token);
        return id;
    }
    private Process controller(String mode, String id, String token, String image, String engine) throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx128m",
                "-cp", System.getProperty("lab.controller.classpath"), Controller.class.getName(),
                mode, id, token, image, engine, root.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) builder.environment().remove(key);
        return builder.start();
    }
    public static class Controller {
        public static void main(String[] args) throws Exception {
            verify(args[1], args[2], args[3], args[4]);
            if (args[0].equals("start")) {
                ok("docker", "start", args[1]);
                Files.writeString(Path.of(args[5]).resolve("started"), "started", StandardOpenOption.CREATE_NEW);
                Thread.sleep(120000);
            } else if (args[0].equals("recover")) remove(args[1], args[2], args[3], args[4]);
            else throw new IllegalArgumentException("Unknown fixture command");
        }
    }
    private static void verify(String id, String token, String image, String engine) throws Exception {
        assertTrue(id.matches("[a-f0-9]{64}"));
        assertEquals(engine, ok("docker", "info", "--format", "{{.ID}}").trim());
        var inspect = WireJson.parse(ok("docker", "inspect", "--format", "{{json .}}", id).getBytes(StandardCharsets.UTF_8));
        assertEquals(id, inspect.path("Id").asText());
        assertEquals(image, inspect.path("Image").asText());
        assertEquals("/lab-isolation-" + token, inspect.path("Name").asText());
        assertEquals(token, inspect.path("Config").path("Labels").path(OWNER).asText());
        assertEquals("65534:65534", inspect.path("Config").path("User").asText());
        var host = inspect.path("HostConfig");
        assertFalse(host.path("Privileged").asBoolean());
        assertTrue(host.path("ReadonlyRootfs").asBoolean());
        assertEquals("", host.path("PidMode").asText());
        assertEquals("none", host.path("NetworkMode").asText());
        assertEquals("no", host.path("RestartPolicy").path("Name").asText());
        assertEquals(32, host.path("PidsLimit").asInt());
        assertEquals(64L * 1024 * 1024, host.path("Memory").asLong());
        assertEquals(500000000L, host.path("NanoCpus").asLong());
        assertTrue(host.path("CapDrop").toString().contains("ALL"));
        assertTrue(host.path("SecurityOpt").toString().contains("no-new-privileges"));
        for (var mount : inspect.path("Mounts")) {
            assertEquals("tmpfs", mount.path("Type").asText());
            assertEquals("/tmp", mount.path("Destination").asText());
        }
    }
    private static void remove(String id, String token, String image, String engine) throws Exception {
        assertEquals(engine, ok("docker", "info", "--format", "{{.ID}}").trim());
        if (ok("docker", "ps", "-aq", "--filter", "id=" + id).isBlank()) return;
        verify(id, token, image, engine);
        ok("docker", "rm", "--force", "--volumes", id);
        assertEquals("", ok("docker", "ps", "-aq", "--filter", "id=" + id).trim());
        System.out.println("stage=containment_cleanup result=CONFIRMED container_id=" + id);
    }
    private static String detached(String id) throws Exception {
        return ok("docker", "exec", id, "sh", "-c",
                "pid=$(cat /tmp/detached.pid); awk '$3 == \"Z\" {exit 1} {print $1, $4, $6}' /proc/$pid/stat").trim();
    }
    private static String awaitDetached(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (command("docker", "exec", id, "test", "-s", "/tmp/detached.pid").code == 0) return detached(id);
            Thread.sleep(50);
        }
        throw new AssertionError("Detached fixture did not start");
    }
    private static void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(Files.exists(path), "Controller did not start fixture");
    }
    private record Command(int code, String output) {}
    private static String ok(String... args) throws Exception {
        var result = command(args); assertEquals(0, result.code, result.output); return result.output;
    }
    private static Command command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        var io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "isolation-test-command"); t.setDaemon(true); return t; });
        try {
            byte[] bytes = io.submit(() -> process.getInputStream().readNBytes(16385)).get(20, TimeUnit.SECONDS);
            assertTrue(bytes.length <= 16384, "Docker response exceeds bound");
            assertTrue(process.waitFor(2, TimeUnit.SECONDS));
            return new Command(process.exitValue(), new String(bytes, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
            io.shutdownNow(); assertTrue(io.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
