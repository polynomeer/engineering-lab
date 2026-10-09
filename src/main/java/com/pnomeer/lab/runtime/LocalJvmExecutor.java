package com.pnomeer.lab.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.worker.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/** Server-registered external executor. No app registration until real worker readiness/cleanup is wired. */
final class LocalJvmExecutor implements ExperimentExecutor {
    record Request(ObjectNode parameters, String definitionSha256, long seed) {
        Request { parameters = parameters.deepCopy(); }
    }
    private final WorkerArtifactImporter artifactImporter;
    private final WorkerManifest manifest;
    private final WorkerProcess.Limits limits;
    private final Path workspaceRoot;
    private final Set<String> metricKeys;
    private final Function<ExperimentScenario, Request> requests;

    LocalJvmExecutor(WorkerManifest manifest, WorkerProcess.Limits limits, Path workspaceRoot,
                     Set<String> metricKeys, Function<ExperimentScenario, Request> requests) {
        this(manifest, limits, workspaceRoot, metricKeys, requests, null);
    }
    LocalJvmExecutor(WorkerManifest manifest, WorkerProcess.Limits limits, Path workspaceRoot,
                     Set<String> metricKeys, Function<ExperimentScenario, Request> requests, WorkerArtifactImporter artifactImporter) {
        this.artifactImporter = artifactImporter;
        this.manifest = Objects.requireNonNull(manifest); this.limits = Objects.requireNonNull(limits);
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot); this.metricKeys = Set.copyOf(metricKeys);
        this.requests = Objects.requireNonNull(requests);
    }
    public Map<String, String> parameters() {
        return Map.of("execution.kind", "LOCAL_JVM", "execution.moduleId", manifest.moduleId(),
                "execution.workerArtifactSha256", manifest.artifactSha256(), "observation.jvm.mode", "OFF",
                "observation.trace.mode", "OFF", "observation.jfr.mode", "OFF", "observation.logs.mode", "OFF");
    }
    public <C extends ExperimentScenario> Outcome execute(Experiment<C> experiment, C scenario, Output output) {
        RunStatus status = RunStatus.FAILED;
        ExperimentSummary summary = ExperimentSummary.empty();
        String failure = null;
        boolean interrupted = false;
        try {
            Request request = requests.apply(scenario);
            ObjectNode command = WireJson.object().put("protocolVersion", 1).put("runId", output.runId())
                    .put("experimentId", experiment.type()).put("definitionSha256", request.definitionSha256())
                    .put("deadlineEpochMs", System.currentTimeMillis() + limits.timeout().toMillis())
                    .put("seed", request.seed()).put("workspace", workspaceRoot.toString());
            command.set("parameters", request.parameters());
            var artifacts = artifactImporter == null ? null : artifactImporter.open(output.runId(), output.artifacts());
            var result = new WorkerProcess(limits).execute(manifest, command, workspaceRoot, new WorkerProcess.EvidenceSink() {
                long checkSequence;
                public void persist(WorkerProtocol.Event event, Path workspace) throws Exception {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    JsonNode payload = event.payload();
                    switch (event.type()) {
                        case "HELLO" -> {
                            JsonNode env = payload.path("environment"), git = payload.path("git");
                            output.worker().accept(new RunExecution.Worker(new RunEnvironment(text(env,"javaVersion"), text(env,"javaVendor"),
                                    text(env,"vmName"), text(env,"osName"), text(env,"osVersion"), text(env,"osArch"),
                                    env.path("availableProcessors").intValue(), env.path("maxHeapBytes").longValue()),
                                    new GitProvenance(nullable(git,"commit"), git.path("dirty").isNull() ? null : git.path("dirty").booleanValue(), nullable(git,"error")),
                                    text(payload,"artifactSha256")));
                        }
                        case "PHASE" -> output.phase().accept(RunExecution.Phase.valueOf(text(payload,"phase")));
                        case "METRIC" -> {
                            var gauges = new HashMap<String, Double>(); var counters = new HashMap<String, Long>(); var labels = new HashMap<String, String>();
                            payload.path("gauges").fields().forEachRemaining(e -> { requireMetric(e.getKey()); gauges.put(e.getKey(), e.getValue().doubleValue()); });
                            payload.path("counters").fields().forEachRemaining(e -> { requireMetric(e.getKey()); counters.put(e.getKey(), e.getValue().longValue()); });
                            payload.path("labels").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().textValue()));
                            output.experimentMetrics().accept(new MetricPoint(payload.path("timestampEpochMs").longValue(), gauges, counters, labels));
                        }
                        case "CHECK" -> output.checks().accept(new RunCheck(checkSequence++, text(payload,"checkId"),
                                RunCheck.Status.valueOf(text(payload,"status")), text(payload,"expected"), text(payload,"observed"), text(payload,"description")));
                        case "ARTIFACT" -> {
                            if (artifacts == null) throw new ProtocolException("ARTIFACT_IMPORT_UNAVAILABLE");
                            artifacts.accept(workspace, payload);
                        }
                        case "RESULT" -> { /* Do not seal on RESULT: await EOF, exit, flush and process cleanup. */ }
                        default -> throw new ProtocolException("EVENT_TYPE");
                    }
                }
                public void flush() { output.drainMetrics().run(); }
            });
            status = RunStatus.valueOf(text(result.outcome(), "status"));
            var counters = new HashMap<String, Long>(); var gauges = new HashMap<String, Double>(); var details = new HashMap<String, String>();
            result.outcome().path("summary").fields().forEachRemaining(e -> {
                JsonNode value = e.getValue();
                if (value.isIntegralNumber()) {
                    if (!value.canConvertToLong()) throw new IllegalArgumentException("Summary integer exceeds storage range");
                    counters.put(e.getKey(), value.longValue());
                } else if (value.isNumber()) gauges.put(e.getKey(), value.doubleValue());
                else details.put(e.getKey(), value.isTextual() ? value.textValue() : value.toString());
            });
            counters.put("worker.stderr.bytes", result.stderrBytes());
            counters.put("worker.stderr.retainedBytes", (long) result.stderr().length);
            details.put("worker.workspace", result.workspace().getFileName().toString());
            summary = new ExperimentSummary(counters, gauges, details);
            failure = status == RunStatus.SUCCEEDED ? null : text(result.outcome(), "reason");
        } catch (InterruptedException e) {
            interrupted = true; Thread.interrupted(); status = RunStatus.INTERRUPTED; failure = "Worker execution interrupted";
        } catch (Exception | OutOfMemoryError e) {
            status = RunStatus.FAILED;
            failure = e instanceof WorkerProcess.Failure || e instanceof ProtocolException ? e.getMessage() : "Worker execution or evidence persistence failed";
            if (e instanceof WorkerProcess.Failure workerFailure && workerFailure.workspace() != null) {
                summary = new ExperimentSummary(Map.of(), Map.of(), Map.of("worker.workspace", workerFailure.workspace().getFileName().toString()));
            }
            interrupted = Thread.interrupted();
        } finally {
            try { output.drainMetrics().run(); }
            catch (RuntimeException e) { status = RunStatus.FAILED; failure = "Worker metric storage failed"; interrupted |= Thread.interrupted(); }
        }
        return new Outcome(status, summary, failure, interrupted);
    }
    private void requireMetric(String key) { if (!metricKeys.contains(key)) throw new IllegalArgumentException("Unregistered worker metric"); }
    private static String text(JsonNode node, String key) { return node.path(key).textValue(); }
    private static String nullable(JsonNode node, String key) { return node.path(key).isNull() ? null : text(node,key); }
}
