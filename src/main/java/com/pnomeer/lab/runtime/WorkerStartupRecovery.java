package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.storage.RunStore;
import com.pnomeer.lab.worker.WorkerManifest;
import com.pnomeer.lab.worker.WireJson;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/** Once-per-startup deployment reconciliation, called only while the RunStore owner lock is held. */
public final class WorkerStartupRecovery {
    public enum Result { NOT_REQUIRED, RECOVERED, REQUIRES_OPERATOR, FAILED }
    @FunctionalInterface interface Reconciler { boolean recover(WorkerManifest manifest, Path workspace, String runId) throws Exception; }
    public record Outcome(String status, String reason) {}
    private final Reconciler reconciler;
    private final java.util.function.Predicate<Outcome> eligible;
    public WorkerStartupRecovery(java.util.function.Predicate<Outcome> eligible) {
        this((manifest, workspace, runId) -> recoverProcess(manifest, workspace, runId, Duration.ofSeconds(60)), eligible);
    }
    WorkerStartupRecovery(Reconciler reconciler, java.util.function.Predicate<Outcome> eligible) {
        this.reconciler = java.util.Objects.requireNonNull(reconciler);
        this.eligible = java.util.Objects.requireNonNull(eligible);
    }

    public Result recover(LocalJvmRegistration binding, RunStore store) {
        var guard = new WorkerRecoveryGuard(LocalJvmRegistration.marker(binding.workspaces(), binding.id()));
        if (!guard.pending()) return Result.NOT_REQUIRED;
        WorkerRecoveryGuard.Pending pending;
        try { pending = guard.completed(binding.manifest().artifactSha256()); }
        catch (Exception e) { return Result.REQUIRES_OPERATOR; }
        // Deployment-owned policy; a generic FAILED result alone never proves external-resource cleanup.
        if (!eligible.test(new Outcome(pending.status(), pending.reason()))) return Result.REQUIRES_OPERATOR;
        var run = store.find(pending.runId());
        if (run.isEmpty() || run.get().completedAtEpochMs() <= 0
                || !java.util.Set.of(com.pnomeer.lab.core.storage.RunStatus.SUCCEEDED, com.pnomeer.lab.core.storage.RunStatus.FAILED,
                        com.pnomeer.lab.core.storage.RunStatus.INTERRUPTED).contains(run.get().status())
                || !binding.manifest().experiments().contains(run.get().experimentType())) return Result.REQUIRES_OPERATOR;
        try {
            if (!reconciler.recover(binding.manifest(), pending.workspace(), pending.runId())) return Result.FAILED;
            if (Thread.currentThread().isInterrupted()) return Result.FAILED;
            pending.ticket().confirmSuccess();
            return Result.RECOVERED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return Result.FAILED;
        } catch (Exception e) { return Result.FAILED; }
    }

    static boolean recoverProcess(WorkerManifest manifest, Path workspace, String runId, Duration timeout) throws Exception {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("Recovery timeout");
        Process child = null; Path directory = null;
        boolean confirmed = false, cleanup = true, interrupted = false;
        var io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "worker-recovery"); t.setDaemon(true); return t; });
        try {
            manifest.preflight(manifest.experiments().iterator().next());
            directory = Files.createTempDirectory("lab-worker-recovery-");
            Path jar = directory.resolve("worker.jar");
            try (var input = Files.newInputStream(manifest.jar(), LinkOption.NOFOLLOW_LINKS);
                 var output = Files.newOutputStream(jar, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192]; long total = 0; int count;
                while ((count = input.read(buffer)) != -1) {
                    if ((total += count) > 256L * 1024 * 1024) throw new java.io.IOException("Recovery jar exceeds limit");
                    output.write(buffer, 0, count);
                }
            }
            new WorkerManifest(manifest.javaExecutable(), jar, manifest.artifactSha256(), manifest.moduleId(), manifest.experiments(), manifest.heapMiB())
                    .preflight(manifest.experiments().iterator().next());
            var builder = new ProcessBuilder(manifest.javaExecutable().toString(), "-Xmx" + manifest.heapMiB() + "m", "-jar", jar.toString(),
                    "--recover", workspace.toRealPath().toString(), runId).directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) builder.environment().remove(key);
            child = builder.start(); child.getOutputStream().close();
            Process process = child;
            long deadline = System.nanoTime() + timeout.toNanos();
            var read = io.submit(() -> process.getInputStream().readNBytes(4097));
            byte[] bytes = read.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (bytes.length <= 4096 && child.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) && child.exitValue() == 0) {
                var response = WireJson.parse(bytes);
                var removed = response.path("removedContainers");
                confirmed = response.size() == 4 && response.path("status").asText().equals("RECOVERED")
                        && response.path("runId").asText().equals(runId)
                        && response.path("admissionReleased").isBoolean() && !response.path("admissionReleased").booleanValue()
                        && removed.isIntegralNumber() && removed.canConvertToInt() && removed.intValue() >= 0 && removed.intValue() <= 1;
            }
        } catch (InterruptedException e) { interrupted = true; }
        catch (Exception e) { confirmed = false; }
        finally {
            interrupted |= Thread.interrupted();
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                try { cleanup = child.waitFor(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { interrupted = true; cleanup = false; }
            }
            io.shutdownNow();
            try { cleanup &= io.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { interrupted = true; cleanup = false; }
            if (directory != null) try { Files.deleteIfExists(directory.resolve("worker.jar")); Files.deleteIfExists(directory); }
            catch (Exception e) { cleanup = false; }
            if (interrupted) Thread.currentThread().interrupt();
        }
        return confirmed && cleanup && !interrupted;
    }
}
