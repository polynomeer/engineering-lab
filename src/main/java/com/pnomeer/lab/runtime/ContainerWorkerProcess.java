package com.pnomeer.lab.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.worker.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Non-TTY exec transport inside a journaled, network-isolated container boundary. */
final class ContainerWorkerProcess {
    private final ContainerWorkerManifest manifest;
    private final WorkerProcess.Limits limits;
    private final ContainerWorkerResources resources;
    ContainerWorkerProcess(ContainerWorkerManifest manifest, WorkerProcess.Limits limits) { this(manifest, limits, ContainerWorkerResources.NONE); }
    ContainerWorkerProcess(ContainerWorkerManifest manifest, WorkerProcess.Limits limits, ContainerWorkerResources resources) {
        this.manifest = manifest; this.limits = limits; this.resources = resources;
    }
    WorkerProcess.Result execute(ObjectNode command, Path root, WorkerProcess.EvidenceSink sink) throws Exception {
        WorkerProtocol.writeCommand(OutputStream.nullOutputStream(), command, limits.protocol());
        if (!manifest.experiments().contains(command.path("experimentId").asText())) throw new ProtocolException("EXPERIMENT_NOT_ALLOWED");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new ProtocolException("WORKSPACE_ROOT");
        Path workspace = Files.createTempDirectory(root.toRealPath(), "worker-");
        return executeWorkspace(command, workspace, sink);
    }
    WorkerProcess.Result executeWorkspace(ObjectNode command, Path workspace, WorkerProcess.EvidenceSink sink) throws Exception {
        WorkerProtocol.writeCommand(OutputStream.nullOutputStream(), command, limits.protocol());
        if (!manifest.experiments().contains(command.path("experimentId").asText())) throw new ProtocolException("EXPERIMENT_NOT_ALLOWED");
        Path journal = Files.createDirectory(workspace.resolve("container-lifecycle"));
        var session = resources.open(command, workspace, manifest.artifactSha256());
        boolean workerRemoved = true;
        var spec = new WorkerContainerLifecycle.Spec(command.path("runId").asText(), manifest.artifactSha256(), manifest.imageId(), List.of("sleep", "900"));
        var io = Executors.newFixedThreadPool(3, r -> { var t = new Thread(r, "container-worker-pipe"); t.setDaemon(true); return t; });
        var reports = new ContainerArtifactTransfer();
        Process child = null;
        try {
            session.prepare();
            var engine = new DockerContainerEngine(session.networkId());
            try (var lifecycle = new WorkerContainerLifecycle(journal, spec, engine)) {
                try {
                    long duration = Math.min(limits.timeout().toNanos(), TimeUnit.MILLISECONDS.toNanos(command.path("deadlineEpochMs").asLong() - System.currentTimeMillis()));
                    if (duration <= 0) throw new ProtocolException("DEADLINE_EXPIRED");
                    long deadline = System.nanoTime() + duration;
                    workerRemoved = false;
                    String id = lifecycle.create(); lifecycle.start();
                    if (System.nanoTime() >= deadline) throw new ProtocolException("DEADLINE_EXCEEDED");
                    // Fixed bootstrap only: neither the shell program nor launch arguments come from scenario input.
                    String bootstrap = "echo '" + manifest.artifactSha256() + "  /opt/lab/worker.jar' | sha256sum -c >/dev/null"
                            + " && mkdir /tmp/worker && ln -s /opt/lab/worker.jar /tmp/worker/worker.jar"
                            + " && exec env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS -u CLASSPATH java -Xmx128m -jar /opt/lab/worker.jar";
                    var launch = new ArrayList<>(List.of("docker", "exec", "-i"));
                    var environment = session.environment();
                    if (environment.size() > 16) throw new ProtocolException("RESOURCE_ENVIRONMENT_LIMIT");
                    for (var entry : environment.entrySet()) {
                        if (!entry.getKey().matches("LAB_[A-Z0-9_]{1,80}") || entry.getValue().length() > 1024 || entry.getValue().indexOf('\0') >= 0)
                            throw new ProtocolException("RESOURCE_ENVIRONMENT");
                        launch.add("--env"); launch.add(entry.getKey() + "=" + entry.getValue());
                    }
                    launch.addAll(List.of(id, "sh", "-c", bootstrap));
                    child = new ProcessBuilder(launch).start();
                    Process process = child;
                    ObjectNode request = command.deepCopy().put("workspace", "/tmp/worker");
                    var identity = new WorkerProtocol.Identity(command.path("runId").asText(), manifest.moduleId(), command.path("experimentId").asText(),
                            command.path("definitionSha256").asText(), manifest.artifactSha256());
                    var diagnostic = new Diagnostic(limits.stderrBytes());
                    var stderr = io.submit(() -> { diagnostic.drain(process.getErrorStream()); return null; });
                    var writer = io.submit(() -> { WorkerProtocol.writeCommand(process.getOutputStream(), request, limits.protocol()); return null; });
                    var reader = io.submit(() -> {
                        var protocol = new WorkerProtocol.Reader(process.getInputStream(), identity, limits.protocol());
                        ObjectNode result = null; WorkerProtocol.Event event;
                        while ((event = protocol.next()) != null) {
                            if (event.type().equals("HELLO") && !event.payload().path("environment").path("javaVersion").asText().matches("21([.+-].*)?"))
                                throw new ProtocolException("CONTAINER_JAVA_VERSION");
                            if (event.type().equals("ARTIFACT")) reports.copy(id, workspace, event.payload());
                            sink.persist(event, workspace);
                            if (event.type().equals("RESULT")) result = event.payload().deepCopy();
                        }
                        if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) throw new TimeoutException();
                        protocol.verifyExit(process.exitValue()); sink.flush(); return result;
                    });
                    await(writer, deadline); ObjectNode outcome = await(reader, deadline); await(stderr, deadline);
                    return new WorkerProcess.Result(outcome, diagnostic.kept.toByteArray(), diagnostic.total, workspace);
                } finally {
                    boolean interrupted = Thread.interrupted();
                    boolean cleaned = true;
                    reports.stop();
                    if (child != null && child.isAlive()) {
                        child.destroyForcibly();
                        try { cleaned = child.waitFor(2, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { interrupted = true; cleaned = false; }
                    }
                    io.shutdownNow();
                    try {
                        // Deletion ends the PID namespace, including unobserved exec descendants.
                        var recovery = lifecycle.reconcile();
                        workerRemoved = recovery != WorkerContainerLifecycle.Recovery.REQUIRES_OPERATOR;
                        cleaned &= workerRemoved;
                    } catch (Exception e) {
                        interrupted |= e instanceof InterruptedException;
                        cleaned = false;
                    }
                    try { cleaned &= io.awaitTermination(limits.stopGrace().toMillis(), TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { interrupted = true; cleaned = false; }
                    if (interrupted) Thread.currentThread().interrupt();
                    if (!cleaned || !reports.stopped()) throw new WorkerProcess.Failure("CONTAINER_CLEANUP_UNCONFIRMED", workspace);
                    if (interrupted) throw new InterruptedException("Container transport interrupted during cleanup");
                }
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ProtocolException p) throw new WorkerProcess.Failure(p.getMessage(), workspace);
            throw new WorkerProcess.Failure("CONTAINER_PIPE_OR_EVIDENCE_FAILED", workspace);
        } catch (TimeoutException e) { throw new WorkerProcess.Failure("DEADLINE_EXCEEDED", workspace); }
        finally {
            reports.stop(); io.shutdownNow();
            boolean interrupted = Thread.interrupted();
            try { session.finish(workerRemoved); }
            catch (Exception e) {
                if (e instanceof InterruptedException) interrupted = true;
                throw new WorkerProcess.Failure("RESOURCE_CLEANUP_UNCONFIRMED", workspace);
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
    private static <T> T await(Future<T> value, long deadline) throws Exception { return value.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
    private static final class Diagnostic {
        final ByteArrayOutputStream kept = new ByteArrayOutputStream(); final int maximum; long total;
        Diagnostic(int maximum) { this.maximum = maximum; }
        void drain(InputStream input) throws IOException {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) { total += n; kept.write(buffer, 0, Math.min(n, maximum - kept.size())); }
        }
    }
}
