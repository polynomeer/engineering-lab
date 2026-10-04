package com.pnomeer.lab.cli;

import com.pnomeer.lab.scenario.*;
import com.pnomeer.lab.registry.ExperimentCatalog;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** HTTP client only: it never starts Spring or opens the evidence database. */
public final class LabCli {
    private static final int DOCUMENT_LIMIT = 65536, INPUT_LIMIT = 52428800, RESPONSE_LIMIT = 1048576;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String USAGE = "lab list [--server URL] | validate FILE | run FILE [--server URL] [--wait [--timeout-seconds N]]";

    public static void main(String[] args) { System.exit(execute(args, System.out, System.err)); }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        HttpClient client = null;
        try {
            Options options = Options.parse(args);
            ScenarioDocuments documents = new ScenarioDocuments(DOCUMENT_LIMIT);
            ScenarioDefinition definition = null;
            Path input = null;
            if (!options.command.equals("list")) {
                Path path = options.file.toRealPath();
                try (var stream = Files.newInputStream(path)) { definition = documents.read(stream, ScenarioDocuments.format(path.toString())); }
                ExperimentCatalog.load().get(definition.experiment()).validate(definition);
                if (definition.input() != null) input = verifyInput(path.getParent(), definition.input());
                if (options.command.equals("validate")) {
                    out.println(JSON.writeValueAsString(Map.of("valid", true, "experiment", definition.experiment(),
                            "scenarioId", definition.scenario().id(), "definitionSha256", documents.hash(definition))));
                    return 0;
                }
            }
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
            if (options.command.equals("list")) {
                out.println(JSON.writeValueAsString(request(client, options.server.resolve("/experiments"), null, null, Duration.ofSeconds(30))));
                return 0;
            }
            String canonical = documents.canonical(definition);
            HttpRequest.BodyPublisher body;
            String contentType;
            if (input == null) {
                body = HttpRequest.BodyPublishers.ofString(canonical, StandardCharsets.UTF_8);
                contentType = "application/json";
            } else {
                String boundary = "lab-" + UUID.randomUUID();
                contentType = "multipart/form-data; boundary=" + boundary;
                body = HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\nContent-Disposition: form-data; name=\"scenario\"; filename=\"scenario.json\"\r\nContent-Type: application/json\r\n\r\n" + canonical
                                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"input.xlsx\"\r\nContent-Type: application/octet-stream\r\n\r\n", StandardCharsets.UTF_8),
                        HttpRequest.BodyPublishers.ofFile(input),
                        HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
            }
            Map<?, ?> submission = object(request(client, options.server.resolve("/runs"), body, contentType, Duration.ofSeconds(30)));
            String runId = UUID.fromString((String)submission.get("runId")).toString();
            out.println(JSON.writeValueAsString(submission));
            if (!options.waitForCompletion) return 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(options.timeoutSeconds);
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    err.println("Wait timed out; run continues: " + runId);
                    return 2;
                }
                Map<?, ?> run;
                try {
                    run = object(request(client, options.server.resolve("/runs/" + runId), null, null,
                            Duration.ofNanos(Math.min(remaining, TimeUnit.SECONDS.toNanos(30)))));
                } catch (TimeoutException ex) {
                    if (System.nanoTime() >= deadline) { err.println("Wait timed out; run continues: " + runId); return 2; }
                    throw ex;
                }
                String status = (String)run.get("status");
                if (Set.of("SUCCEEDED", "FAILED", "INTERRUPTED").contains(status)) {
                    out.println(JSON.writeValueAsString(run));
                    return status.equals("SUCCEEDED") ? 0 : 1;
                }
                if (!Set.of("QUEUED", "RUNNING").contains(status)) throw new IOException("Unexpected run status: " + status);
                Thread.sleep(Math.max(1, Math.min(200, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); err.println("CLI interrupted; submitted runs are not cancelled"); return 1;
        } catch (Exception ex) {
            err.println(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()); return 1;
        } finally { if (client != null) client.shutdownNow(); }
    }

    static Path verifyInput(Path directory, ScenarioDefinition.Input input) throws Exception {
        Path root = directory.toRealPath(), file = root.resolve(input.file()).toRealPath();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) throw new ScenarioException("Input must be a regular file inside the scenario directory");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        byte[] buffer = new byte[8192];
        try (var stream = Files.newInputStream(file)) {
            for (int size; (size = stream.read(buffer)) != -1;) {
                count += size;
                if (count > INPUT_LIMIT) throw new ScenarioException("Input exceeds CLI limit: " + INPUT_LIMIT);
                digest.update(buffer, 0, size);
            }
        }
        if (count == 0 || !HexFormat.of().formatHex(digest.digest()).equals(input.sha256())) throw new ScenarioException("Input SHA-256 mismatch");
        return file;
    }

    private static Object request(HttpClient client, URI uri, HttpRequest.BodyPublisher body, String type, Duration timeout) throws Exception {
        var request = HttpRequest.newBuilder(uri).header("Accept", "application/json");
        if (body == null) request.GET(); else request.header("Content-Type", type).POST(body);
        var future = client.sendAsync(request.build(), response -> new LimitedBodySubscriber());
        HttpResponse<byte[]> response;
        try { response = future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS); }
        catch (Exception ex) { future.cancel(true); throw ex; }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = new String(response.body(), StandardCharsets.UTF_8);
            throw new IOException("HTTP " + response.statusCode() + ": " + detail.substring(0, Math.min(1024, detail.length())));
        }
        return JSON.readValue(response.body(), Object.class);
    }
    private static Map<?, ?> object(Object value) throws IOException {
        if (!(value instanceof Map<?, ?> map)) throw new IOException("Expected a JSON object response");
        return map;
    }

    /** Cancels oversized responses before accumulating more than the fixed byte limit. */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > RESPONSE_LIMIT - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Response exceeds CLI byte limit")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    private record Options(String command, Path file, URI server, boolean waitForCompletion, int timeoutSeconds) {
        static Options parse(String[] args) {
            if (args.length == 0 || !Set.of("run", "validate", "list").contains(args[0])) throw new ScenarioException(USAGE);
            String command = args[0]; int index = 1; Path file = null;
            if (!command.equals("list")) {
                if (args.length <= index || args[index].startsWith("--")) throw new ScenarioException(USAGE);
                file = Path.of(args[index++]);
            }
            URI server = URI.create("http://localhost:8080"); boolean wait = false; int timeout = 120;
            Set<String> seen = new HashSet<>();
            while (index < args.length) {
                String key = args[index++];
                if (!seen.add(key)) throw new ScenarioException("Duplicate option: " + key);
                if (key.equals("--wait") && command.equals("run")) { wait = true; continue; }
                if (index >= args.length) throw new ScenarioException(USAGE);
                String value = args[index++];
                if (key.equals("--server") && !command.equals("validate")) server = URI.create(value);
                else if (key.equals("--timeout-seconds") && command.equals("run")) timeout = Integer.parseInt(value);
                else throw new ScenarioException("Unsupported option: " + key);
            }
            if (seen.contains("--timeout-seconds") && !wait) throw new ScenarioException("--timeout-seconds requires --wait");
            if (timeout < 1 || timeout > 3600) throw new ScenarioException("timeout must be between 1 and 3600 seconds");
            if (!("http".equals(server.getScheme()) || "https".equals(server.getScheme())) || server.getHost() == null
                    || server.getUserInfo() != null || server.getQuery() != null || server.getFragment() != null
                    || !(server.getPath().isEmpty() || server.getPath().equals("/"))) throw new ScenarioException("--server requires an HTTP(S) origin URL");
            return new Options(command, file, server, wait, timeout);
        }
    }
}
