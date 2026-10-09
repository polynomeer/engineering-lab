package com.pnomeer.lab.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Parent-side process transport. The caller owns admission and durable Run lifecycle. */
public final class WorkerProcess {
    public record Limits(Duration timeout, Duration stopGrace, int stderrBytes, ProtocolLimits protocol) {
        public static final Limits DEFAULT = new Limits(Duration.ofMinutes(15), Duration.ofSeconds(30), 1048576, ProtocolLimits.DEFAULT);
        public Limits {
            Objects.requireNonNull(timeout); Objects.requireNonNull(stopGrace); Objects.requireNonNull(protocol);
            if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(15)) > 0
                    || stopGrace.isNegative() || stopGrace.isZero() || stopGrace.compareTo(Duration.ofSeconds(30)) > 0
                    || stderrBytes < 0 || stderrBytes > 1048576) throw new IllegalArgumentException("Invalid process limits");
        }
    }
    /** Must be interruptible and bounded. persist includes artifact verification; flush acknowledges durable writes. */
    public interface EvidenceSink {
        void persist(WorkerProtocol.Event event, Path workspace) throws Exception;
        void flush() throws Exception;
    }
    public record Result(ObjectNode outcome, byte[] stderr, long stderrBytes, Path workspace) {
        public boolean stderrTruncated() { return stderrBytes > stderr.length; }
    }
    public static final class Failure extends IOException {
        private final Path workspace;
        public Failure(String reason, Path workspace) { super(reason); this.workspace = workspace; }
        public Path workspace() { return workspace; }
    }
    private final Limits limits;
    public WorkerProcess(Limits limits) { this.limits = Objects.requireNonNull(limits); }

    /** workspaceRoot must be an administrator-owned directory; each run gets a fresh private directory. */
    public Result execute(WorkerManifest manifest, ObjectNode command, Path workspaceRoot, EvidenceSink sink)
            throws IOException, InterruptedException {
        WireJson.command(command);
        String experiment = command.path("experimentId").textValue();
        manifest.preflight(experiment);
        long remainingMs = command.path("deadlineEpochMs").longValue() - System.currentTimeMillis();
        if (remainingMs <= 0) throw new ProtocolException("DEADLINE_EXPIRED");
        long duration = Math.min(limits.timeout().toNanos(), TimeUnit.MILLISECONDS.toNanos(remainingMs));
        long deadline = System.nanoTime() + duration;
        if (!Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS)) throw new ProtocolException("WORKSPACE_ROOT");
        Path workspace = Files.createTempDirectory(workspaceRoot.toRealPath(), "worker-");
        // Launch the verified copy, not a mutable shared deployment file.
        Path jar = workspace.resolve("worker.jar");
        try (InputStream input = Files.newInputStream(manifest.jar()); OutputStream output = Files.newOutputStream(jar, StandardOpenOption.CREATE_NEW)) {
            byte[] block = new byte[8192]; long total = 0; int count;
            while ((count = input.read(block)) != -1) {
                if ((total += count) > 256L * 1024 * 1024) throw new Failure("JAR_LIMIT", workspace);
                output.write(block, 0, count);
            }
        }
        if (!WorkerManifest.sha256(jar).equals(manifest.artifactSha256())) throw new Failure("JAR_HASH_MISMATCH", workspace);
        ObjectNode request = command.deepCopy(); request.put("workspace", workspace.toString());
        var identity = new WorkerProtocol.Identity(request.path("runId").textValue(), manifest.moduleId(), experiment,
                request.path("definitionSha256").textValue(), manifest.artifactSha256());
        var builder = new ProcessBuilder(manifest.javaExecutable().toString(), "-Xmx" + manifest.heapMiB() + "m", "-jar", jar.toString());
        builder.directory(workspace.toFile());
        // Prevent ambient Java options from changing the registered launch command.
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) builder.environment().remove(key);
        if (System.nanoTime() >= deadline) throw new Failure("DEADLINE_EXCEEDED", workspace);
        Process process;
        try { process = builder.start(); }
        catch (IOException e) { throw new Failure("START_FAILED", workspace); }
        ExecutorService io = Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "worker-pipe"); thread.setDaemon(true); return thread;
        });
        var diagnostics = new Diagnostics(limits.stderrBytes());
        Future<?> errorReader = io.submit(() -> { diagnostics.drain(process.getErrorStream()); return null; });
        Future<?> commandWriter = io.submit(() -> { WorkerProtocol.writeCommand(process.getOutputStream(), request, limits.protocol()); return null; });
        Future<ObjectNode> outputReader = io.submit(() -> {
            var reader = new WorkerProtocol.Reader(new BufferedInputStream(process.getInputStream(), 8192), identity, limits.protocol());
            ObjectNode result = null; WorkerProtocol.Event event;
            while ((event = reader.next()) != null) {
                if (event.type().equals("RESULT")) result = event.payload().deepCopy();
                sink.persist(event, workspace);
            }
            // The same deadline covers EOF, exit and final storage acknowledgement.
            if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) throw new TimeoutException();
            reader.verifyExit(process.exitValue());
            sink.flush();
            return result;
        });
        boolean interrupted = false;
        try {
            await(commandWriter, deadline);
            ObjectNode outcome = await(outputReader, deadline);
            await(errorReader, deadline);
            return new Result(outcome, diagnostics.bytes(), diagnostics.total(), workspace);
        } catch (InterruptedException e) { interrupted = true; throw e; }
        catch (TimeoutException e) { throw new Failure("DEADLINE_EXCEEDED", workspace); }
        catch (ExecutionException e) {
            String reason = e.getCause() instanceof ProtocolException p ? p.getMessage()
                    : e.getCause() instanceof TimeoutException ? "DEADLINE_EXCEEDED" : "PIPE_OR_EVIDENCE_FAILED";
            throw new Failure(reason, workspace);
        } finally {
            // Stop the child before closing stdin: a writer may be blocked on a full pipe.
            if (process.isAlive()) process.destroy();
            try {
                if (!process.waitFor(limits.stopGrace().toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    if (!process.waitFor(limits.stopGrace().toMillis(), TimeUnit.MILLISECONDS)) throw new Failure("PROCESS_CLEANUP_UNCONFIRMED", workspace);
                }
            } catch (InterruptedException e) {
                interrupted = true; process.destroyForcibly();
                if (!process.waitFor(limits.stopGrace().toMillis(), TimeUnit.MILLISECONDS))
                    throw new Failure("PROCESS_CLEANUP_UNCONFIRMED", workspace);
                throw e;
            } finally {
                outputReader.cancel(true); errorReader.cancel(true); commandWriter.cancel(true); io.shutdownNow();
                // A sink must honor interruption. Never report cleanup success while it can still publish evidence.
                try {
                    if (!io.awaitTermination(limits.stopGrace().toMillis(), TimeUnit.MILLISECONDS))
                        throw new Failure("IO_CLEANUP_UNCONFIRMED", workspace);
                } catch (InterruptedException e) { interrupted = true; throw e; }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
    }
    private static <T> T await(Future<T> future, long deadline) throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }
    private static final class Diagnostics {
        private final int maximum;
        private final ByteArrayOutputStream kept;
        private long total;
        Diagnostics(int maximum) { this.maximum = maximum; kept = new ByteArrayOutputStream(Math.min(maximum, 8192)); }
        void drain(InputStream input) throws IOException {
            byte[] block = new byte[8192]; int count;
            while ((count = input.read(block)) != -1) {
                total += count;
                kept.write(block, 0, Math.min(count, maximum - kept.size()));
            }
        }
        byte[] bytes() { return kept.toByteArray(); }
        long total() { return total; }
    }
}
