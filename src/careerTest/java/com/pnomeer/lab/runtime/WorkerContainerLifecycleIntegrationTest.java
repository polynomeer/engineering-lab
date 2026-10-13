package com.pnomeer.lab.runtime;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WorkerContainerLifecycleIntegrationTest {
    @TempDir Path root;
    static WorkerContainerLifecycle.Spec spec(String image) {
        return new WorkerContainerLifecycle.Spec("lifecycle-integration", "a".repeat(64), image, List.of("sleep", "120"));
    }
    @ParameterizedTest @ValueSource(booleans={false, true})
    void freshJvmRecoversPersistedCreatedOrStartedContainer(boolean start) throws Exception {
        Process lookup = new ProcessBuilder("docker", "image", "inspect", "busybox:1.37", "--format", "{{.Id}}")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String image;
        try {
            assertTrue(lookup.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, lookup.exitValue());
            image = new String(lookup.getInputStream().readNBytes(128), java.nio.charset.StandardCharsets.UTF_8).trim();
        } finally { if (lookup.isAlive()) { lookup.destroyForcibly(); assertTrue(lookup.waitFor(5, TimeUnit.SECONDS)); } }
        var engine = new DockerContainerEngine();
        String id = null;
        try {
            try (var lifecycle = new WorkerContainerLifecycle(root, spec(image), engine)) {
                id = lifecycle.create();
                System.out.println("stage=lifecycle_created container_id=" + id);
                if (start) lifecycle.start();
                assertEquals(start ? WorkerContainerLifecycle.State.STARTED : WorkerContainerLifecycle.State.CREATED, lifecycle.state());
            }
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx128m",
                    "-cp", System.getProperty("lab.controller.classpath"), Recovery.class.getName(), root.toString(), image)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) builder.environment().remove(key);
            Process child = builder.start();
            try { assertTrue(child.waitFor(60, TimeUnit.SECONDS)); assertEquals(0, child.exitValue()); }
            finally { if (child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); } }
            assertTrue(engine.inspect(id).isEmpty());
            try (var lifecycle = new WorkerContainerLifecycle(root, spec(image), engine)) {
                assertEquals(WorkerContainerLifecycle.State.REMOVED, lifecycle.state());
                assertEquals(WorkerContainerLifecycle.Recovery.REMOVED, lifecycle.reconcile());
            }
        } finally {
            try (var cleanup = new WorkerContainerLifecycle(root, spec(image), engine)) {
                assertNotEquals(WorkerContainerLifecycle.Recovery.REQUIRES_OPERATOR, cleanup.reconcile());
            }
            if (id != null) assertTrue(engine.inspect(id).isEmpty());
        }
    }
    public static class Recovery {
        public static void main(String[] args) throws Exception {
            try (var lifecycle = new WorkerContainerLifecycle(Path.of(args[0]), spec(args[1]), new DockerContainerEngine())) {
                if (lifecycle.reconcile() != WorkerContainerLifecycle.Recovery.REMOVED) throw new IllegalStateException("Recovery incomplete");
            }
        }
    }
}
