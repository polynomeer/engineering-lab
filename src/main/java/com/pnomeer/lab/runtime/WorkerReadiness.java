package com.pnomeer.lab.runtime;

import com.pnomeer.lab.worker.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounded probe of the same deployment/JVM/Docker environment used for execution. */
public final class WorkerReadiness implements Supplier<LocalJvmRegistration.Readiness> {
    private final WorkerManifest manifest;
    private final Duration timeout;
    private LocalJvmRegistration.Readiness cached;
    private long expires;
    private boolean cleanupFailed;
    public WorkerReadiness(WorkerManifest manifest, Duration timeout) {
        this.manifest = manifest; this.timeout = timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("Probe timeout");
    }
    @Override public synchronized LocalJvmRegistration.Readiness get() {
        if (cleanupFailed) return state("INCOMPATIBLE", "PROBE_CLEANUP_UNCONFIRMED");
        if (cached != null && System.nanoTime() < expires) return cached;
        cached = probe();
        if (cleanupFailed) cached = state("INCOMPATIBLE", "PROBE_CLEANUP_UNCONFIRMED");
        expires = System.nanoTime() + Duration.ofSeconds(5).toNanos(); return cached;
    }
    private LocalJvmRegistration.Readiness probe() {
        Process child = null; Path directory = null;
        var io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "worker-readiness"); t.setDaemon(true); return t; });
        try {
            if (!Files.isRegularFile(manifest.jar(), LinkOption.NOFOLLOW_LINKS)) return state("NOT_BUILT", "WORKER_JAR_MISSING");
            manifest.preflight(manifest.experiments().iterator().next());
            directory = Files.createTempDirectory("lab-worker-probe-");
            Path jar = directory.resolve("worker.jar");
            try (var input = Files.newInputStream(manifest.jar());
                 var output = Files.newOutputStream(jar, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192]; long total = 0; int count;
                while ((count = input.read(buffer)) != -1) {
                    if ((total += count) > 256L * 1024 * 1024) throw new ProtocolException("JAR_LIMIT");
                    output.write(buffer, 0, count);
                }
            }
            new WorkerManifest(manifest.javaExecutable(), jar, manifest.artifactSha256(), manifest.moduleId(), manifest.experiments(), manifest.heapMiB())
                    .preflight(manifest.experiments().iterator().next());
            var builder = new ProcessBuilder(manifest.javaExecutable().toString(), "-Xmx" + manifest.heapMiB() + "m", "-jar", jar.toString(), "--probe")
                    .directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            for (String key : java.util.List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) builder.environment().remove(key);
            child = builder.start(); child.getOutputStream().close();
            Process process = child;
            long deadline = System.nanoTime() + timeout.toNanos();
            var read = io.submit(() -> process.getInputStream().readNBytes(4097));
            byte[] bytes = read.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (bytes.length > 4096 || !child.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) || child.exitValue() != 0)
                return state("INCOMPATIBLE", "WORKER_PROBE_FAILED");
            var response = WireJson.parse(bytes);
            if (response.path("protocolVersion").asInt() != 1 || response.path("javaMajor").asInt() != 21
                    || !manifest.moduleId().equals(response.path("moduleId").asText())) return state("INCOMPATIBLE", "WORKER_PROBE_IDENTITY");
            return response.path("ready").asBoolean(false) ? state("READY", null) : state("DOCKER_UNAVAILABLE", "WORKER_DOCKER_NOT_READY");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return state("INCOMPATIBLE", "PROBE_INTERRUPTED");
        } catch (TimeoutException e) { return state("DOCKER_UNAVAILABLE", "PROBE_TIMEOUT"); }
        catch (Exception e) { return state("INCOMPATIBLE", "DEPLOYMENT_INVALID"); }
        finally {
            boolean interrupted = Thread.interrupted();
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                try { if (!child.waitFor(2, TimeUnit.SECONDS)) cleanupFailed = true; }
                catch (InterruptedException e) { interrupted = true; cleanupFailed = true; }
            }
            io.shutdownNow();
            try { if (!io.awaitTermination(2, TimeUnit.SECONDS)) cleanupFailed = true; }
            catch (InterruptedException e) { interrupted = true; cleanupFailed = true; }
            if (directory != null) try { Files.deleteIfExists(directory.resolve("worker.jar")); Files.deleteIfExists(directory); } catch (Exception ignored) {}
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static LocalJvmRegistration.Readiness state(String state, String reason) { return new LocalJvmRegistration.Readiness(state, reason); }
}
