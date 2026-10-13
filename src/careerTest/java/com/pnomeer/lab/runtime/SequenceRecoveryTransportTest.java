package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.RunStatus;
import com.pnomeer.lab.storage.JdbcRunStore;
import com.pnomeer.lab.worker.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real deployment/JVM/HTTP transport with a loopback Docker API double; never stops the shared daemon. */
class SequenceRecoveryTransportTest {
    private static final String EXPERIMENT = "career.mcp-platform.sequence";
    private static final String RUN = "fault-run", ENGINE = "fault-test-engine", CONTAINER = "c".repeat(64);
    @TempDir Path root;
    enum Fault { LIST_UNAVAILABLE, LIST_HANG, DELETE_UNAVAILABLE, DELETE_RESPONSE_LOST, ENGINE_CHANGED }

    @ParameterizedTest @EnumSource(Fault.class)
    void dockerFailureKeepsAdmissionAndNextProcessReconcilesWithoutRewritingEvidence(Fault fault) throws Exception {
        var manifest = manifest(Path.of(System.getProperty("lab.sequence.manifest")), System.getProperty("lab.worker.java"));
        Path workspaces = Files.createDirectories(root.resolve("worker-workspaces"));
        Path workspace = Files.createDirectory(workspaces.resolve("worker-fault"));
        String token = UUID.randomUUID().toString();
        var journal = new Properties();
        journal.setProperty("version", "1"); journal.setProperty("runId", RUN);
        journal.setProperty("artifactSha256", manifest.artifactSha256());
        journal.setProperty("daemonId", ENGINE); journal.setProperty("token", token);
        Path journalFile = workspace.resolve("sequence-container.properties");
        try (var output = Files.newOutputStream(journalFile)) { journal.store(output, "Synthetic Docker ownership fixture"); }
        byte[] journalBefore = Files.readAllBytes(journalFile);
        var guard = new WorkerRecoveryGuard(LocalJvmRegistration.marker(workspaces, "sequence-worker"));
        guard.begin(RUN, manifest.artifactSha256()).recordCompletion(workspace, "FAILED", "EXPECTATION_NOT_MET");
        Path marker = LocalJvmRegistration.marker(workspaces, "sequence-worker");
        Path receipt = marker.resolveSibling(marker.getFileName() + ".completed");
        byte[] markerBefore = Files.readAllBytes(marker), receiptBefore = Files.readAllBytes(receipt);
        try (var store = new JdbcRunStore(root, 20, 4)) {
            store.create(EXPERIMENT, "fault", new RunMetadata(RUN, Map.of(), RunEnvironment.capture()));
            store.start(RUN); store.complete(RUN, RunStatus.FAILED, ExperimentSummary.empty(), "Keep original failure");
        }
        try (var docker = new DockerEndpoint(fault, token, manifest.artifactSha256())) {
            assertEquals("FAILED", attempt(docker));
            assertTrue(docker.faults.get() > 0, "Fault must be reached through the actual Docker transport");
            assertTrue(guard.pending());
            assertArrayEquals(markerBefore, Files.readAllBytes(marker));
            assertArrayEquals(receiptBefore, Files.readAllBytes(receipt));
            if (fault == Fault.LIST_UNAVAILABLE || fault == Fault.LIST_HANG) assertEquals(0, docker.deletes.get());
            if (fault == Fault.DELETE_UNAVAILABLE) assertTrue(docker.present);
            if (fault == Fault.DELETE_RESPONSE_LOST || fault == Fault.ENGINE_CHANGED) assertFalse(docker.present);
            int deletesBeforeRetry = docker.deletes.get();
            docker.restore();
            assertEquals("RECOVERED", attempt(docker));
            assertFalse(guard.pending());
            assertFalse(docker.present);
            if (fault == Fault.DELETE_RESPONSE_LOST || fault == Fault.ENGINE_CHANGED)
                assertEquals(deletesBeforeRetry, docker.deletes.get(), "Already removed container must not be deleted twice");
            assertNull(docker.unexpected.get(), "Only scoped recovery requests are allowed");
        }
        assertArrayEquals(journalBefore, Files.readAllBytes(journalFile));
        try (var store = new JdbcRunStore(root, 20, 4)) {
            var run = store.find(RUN).orElseThrow();
            assertEquals(RunStatus.FAILED, run.status()); assertEquals("Keep original failure", run.failureMessage());
        }
    }

    private String attempt(DockerEndpoint docker) throws Exception {
        Path result = root.resolve("attempt-result"); Files.deleteIfExists(result);
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-cp", System.getProperty("lab.controller.classpath"), Controller.class.getName(),
                root.toString(), System.getProperty("lab.sequence.manifest"), System.getProperty("lab.worker.java"))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        var env = builder.environment();
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "DOCKER_CONTEXT",
                "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH")) env.remove(key);
        env.put("DOCKER_HOST", "tcp://127.0.0.1:" + docker.server.getAddress().getPort());
        env.put("TESTCONTAINERS_RYUK_DISABLED", "true");
        env.put("TESTCONTAINERS_CHECKS_DISABLE", "true");
        Process child = builder.start();
        try {
            assertTrue(child.waitFor(80, TimeUnit.SECONDS), "Recovery controller exceeded deadline");
            assertEquals(0, child.exitValue(), "Recovery controller fixture failed");
            return Files.readString(result);
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); }
        }
    }
    private static WorkerManifest manifest(Path descriptor, String java) throws Exception {
        var data = WireJson.parse(Files.readAllBytes(descriptor));
        return new WorkerManifest(Path.of(java), descriptor.getParent().resolve(data.path("jarFile").asText()),
                data.path("artifactSha256").asText(), "mcp-platform", Set.of(EXPERIMENT), 256);
    }
    public static class Controller {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]); var manifest = manifest(Path.of(args[1]), args[2]);
            var binding = new LocalJvmRegistration("sequence-worker", manifest, root.resolve("worker-workspaces"), root,
                    Set.of(), ignored -> null, () -> new LocalJvmRegistration.Readiness("READY", null), List.of());
            try (var store = new JdbcRunStore(root, 20, 4)) {
                store.recoverInterrupted();
                var result = new WorkerStartupRecovery(o -> o.status().equals("FAILED") && o.reason().equals("EXPECTATION_NOT_MET"))
                        .recover(binding, store);
                Files.writeString(root.resolve("attempt-result"), result.name());
            }
        }
    }

    private static final class DockerEndpoint implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger faults = new AtomicInteger(), deletes = new AtomicInteger(), requests = new AtomicInteger();
        final AtomicReference<String> unexpected = new AtomicReference<>();
        final Fault fault; final String token, hash;
        final java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
        volatile boolean healthy, present = true;
        DockerEndpoint(Fault fault, String token, String hash) throws IOException {
            this.fault = fault; this.token = token; this.hash = hash;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            server.createContext("/", this::respond); server.start();
        }
        private void respond(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (requests.incrementAndGet() > 64 || exchange.getRequestBody().readNBytes(4097).length > 4096) {
                    unexpected.compareAndSet(null, "Request bound exceeded"); send(exchange, 429, "{}"); return;
                }
                String path = exchange.getRequestURI().getPath().replaceFirst("^/v[0-9]+\\.[0-9]+", "");
                String method = exchange.getRequestMethod();
                if (method.equals("GET") && path.equals("/_ping")) { send(exchange, 200, "OK"); return; }
                if (method.equals("GET") && path.equals("/version")) {
                    send(exchange, 200, "{\"Version\":\"25.0.0\",\"ApiVersion\":\"1.44\",\"Os\":\"linux\",\"Arch\":\"amd64\"}"); return;
                }
                if (method.equals("GET") && path.equals("/info")) {
                    boolean changed = !healthy && fault == Fault.ENGINE_CHANGED && !present;
                    if (changed) faults.incrementAndGet();
                    send(exchange, 200, "{\"ID\":\"" + (changed ? "different-engine" : ENGINE)
                            + "\",\"OSType\":\"linux\",\"OperatingSystem\":\"Docker API fixture\",\"MemTotal\":8589934592,\"NCPU\":2}"); return;
                }
                // With Ryuk disabled only in this fixture, Testcontainers performs its own session-filtered
                // shutdown queries. They must not return our sequence resource or accept an unfiltered scan.
                if (method.equals("POST") && Set.of("/networks/prune", "/volumes/prune", "/images/prune").contains(path)
                        && sessionCleanup(exchange.getRequestURI().getRawQuery())) {
                    send(exchange, 200, "{\"NetworksDeleted\":[],\"VolumesDeleted\":[],\"ImagesDeleted\":[],\"SpaceReclaimed\":0}"); return;
                }
                if (method.equals("GET") && Set.of("/containers/json", "/networks", "/volumes").contains(path)
                        && sessionCleanup(exchange.getRequestURI().getRawQuery())) {
                    send(exchange, 200, path.equals("/volumes") ? "{\"Volumes\":[],\"Warnings\":[]}" : "[]"); return;
                }
                if (method.equals("GET") && path.equals("/containers/json")) {
                    String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                    if (!query.contains("limit=2") || !query.contains("engineering-lab.owner-token=" + token)) {
                        unexpected.compareAndSet(null, "Unscoped list: " + query); send(exchange, 400, "{}"); return;
                    }
                    if (!healthy && fault == Fault.LIST_HANG) {
                        faults.incrementAndGet();
                        try { resume.await(70, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return;
                    }
                    if (!healthy && fault == Fault.LIST_UNAVAILABLE) { faults.incrementAndGet(); send(exchange, 503, "{\"message\":\"Injected list outage\"}"); return; }
                    send(exchange, 200, present ? "[{\"Id\":\"" + CONTAINER + "\"}]" : "[]"); return;
                }
                if (method.equals("GET") && path.equals("/containers/" + CONTAINER + "/json")) {
                    var response = WireJson.object().put("Id", CONTAINER).put("Name", "/engineering-lab-sequence-" + token);
                    var config = response.putObject("Config").put("Image", "mysql:8.0.39");
                    config.putObject("Labels").put("engineering-lab.run-id", RUN).put("engineering-lab.owner-token", token)
                            .put("engineering-lab.artifact-sha256", hash).put("engineering-lab.resource", "sequence-mysql-v1");
                    send(exchange, 200, new String(WireJson.encode(response), StandardCharsets.UTF_8)); return;
                }
                if (method.equals("DELETE") && path.equals("/containers/" + CONTAINER)) {
                    String query = exchange.getRequestURI().getRawQuery();
                    if (query == null || !query.contains("force=true") || !query.contains("v=true")) {
                        unexpected.compareAndSet(null, "Missing force/volume removal flags"); send(exchange, 400, "{}"); return;
                    }
                    deletes.incrementAndGet();
                    if (!healthy && fault == Fault.DELETE_UNAVAILABLE) { faults.incrementAndGet(); send(exchange, 503, "{\"message\":\"Injected delete outage\"}"); return; }
                    present = false;
                    if (!healthy && fault == Fault.DELETE_RESPONSE_LOST) { faults.incrementAndGet(); return; }
                    exchange.sendResponseHeaders(204, -1); return;
                }
                unexpected.compareAndSet(null, method + " " + path + "?" + exchange.getRequestURI().getRawQuery()); send(exchange, 400, "{\"message\":\"Unexpected API\"}");
            }
        }
        private static boolean sessionCleanup(String rawQuery) throws IOException {
            if (rawQuery == null) return false;
            for (String part : rawQuery.split("&")) {
                if (!part.startsWith("filters=")) continue;
                var filters = WireJson.parse(URLDecoder.decode(part.substring(8), StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
                var labels = filters.path("label");
                if (filters.size() != 1 || !labels.isArray() || labels.size() != 4) return false;
                var actual = new HashSet<String>(); labels.forEach(value -> actual.add(value.asText()));
                return actual.contains("org.testcontainers=true") && actual.contains("org.testcontainers.lang=java")
                        && actual.contains("org.testcontainers.version=1.19.8")
                        && actual.stream().anyMatch(label -> label.matches("org\\.testcontainers\\.sessionId=[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"));
            }
            return false;
        }
        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        }
        void restore() { healthy = true; resume.countDown(); }
        public void close() { resume.countDown(); server.stop(0); }
    }
}
