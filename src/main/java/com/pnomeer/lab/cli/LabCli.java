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
    private static final String USAGE = "lab list [--server URL] | validate FILE | run FILE [--server URL] [--repeat N [--warmup M]] "
            + "[--wait [--timeout-seconds N] [--check-baseline | --check-baseline-file FILE [--tolerance-pct P]]] "
            + "| baseline SERIES_ID [--export FILE] [--server URL] "
            + "| sweep FILE --grid NAME=V1,V2 [--grid ...] [--repeat N] [--warmup M] [--server URL] [--wait [--timeout-seconds N]] "
            + "| retention --older-than-days N [--apply] [--server URL]";
    /** Exit codes: 0 success, 1 failed run or error, 2 wait timeout, 3 regression, 4 no baseline or too few repetitions. */
    static final int EXIT_REGRESSION = 3, EXIT_INCONCLUSIVE = 4;

    public static void main(String[] args) { System.exit(execute(args, System.out, System.err)); }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        HttpClient client = null;
        try {
            Options options = Options.parse(args);
            ScenarioDocuments documents = new ScenarioDocuments(DOCUMENT_LIMIT);
            ScenarioDefinition definition = null;
            Path input = null;
            if (options.command.equals("run") || options.command.equals("validate") || options.command.equals("sweep")) {
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
            if (options.command.equals("retention")) {
                URI preview = options.server.resolve("/maintenance/retention?olderThanDays=" + options.olderThanDays);
                Map<?, ?> listed = object(request(client, preview, null, null, Duration.ofSeconds(30)));
                out.println(JSON.writeValueAsString(listed));
                if (!options.apply) return 0;
                URI apply = options.server.resolve("/maintenance/retention?olderThanDays=" + options.olderThanDays
                        + "&token=" + java.net.URLEncoder.encode(String.valueOf(listed.get("token")), StandardCharsets.UTF_8));
                out.println(JSON.writeValueAsString(request(client, apply, HttpRequest.BodyPublishers.noBody(), null, Duration.ofSeconds(60), "POST")));
                return 0;
            }
            if (options.command.equals("baseline") && options.baselineFile != null) {
                var file = request(client, options.server.resolve("/run-series/" + UUID.fromString(options.seriesId) + "/baseline-file"),
                        null, null, Duration.ofSeconds(30));
                Files.writeString(options.baselineFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(file) + "\n", StandardCharsets.UTF_8);
                out.println(JSON.writeValueAsString(Map.of("seriesId", options.seriesId, "exported", options.baselineFile.toString())));
                return 0;
            }
            if (options.command.equals("baseline")) {
                var series = request(client, options.server.resolve("/run-series/" + UUID.fromString(options.seriesId) + "/baseline"),
                        HttpRequest.BodyPublishers.noBody(), null, Duration.ofSeconds(30), "PUT");
                out.println(JSON.writeValueAsString(series));
                return 0;
            }
            String canonical = documents.canonical(definition);
            if (options.command.equals("sweep")) return sweep(client, options, canonical, input, out, err);
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
            if (options.repeat != null) return series(client, options, body, contentType, out, err);
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

    private static int series(HttpClient client, Options options, HttpRequest.BodyPublisher body, String contentType,
            PrintStream out, PrintStream err) throws Exception {
        URI submit = options.server.resolve("/run-series?repetitions=" + options.repeat + "&warmup=" + options.warmup);
        Map<?, ?> submission = object(request(client, submit, body, contentType, Duration.ofSeconds(30)));
        String seriesId = UUID.fromString((String)submission.get("seriesId")).toString();
        out.println(JSON.writeValueAsString(submission));
        if (!options.waitForCompletion) return 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(options.timeoutSeconds);
        Map<?, ?> view;
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) { err.println("Wait timed out; series continues: " + seriesId); return 2; }
            try {
                view = object(request(client, options.server.resolve("/run-series/" + seriesId), null, null,
                        Duration.ofNanos(Math.min(remaining, TimeUnit.SECONDS.toNanos(30)))));
            } catch (TimeoutException ex) {
                if (System.nanoTime() >= deadline) { err.println("Wait timed out; series continues: " + seriesId); return 2; }
                throw ex;
            }
            String status = (String)object(view.get("series")).get("status");
            if (Set.of("SUCCEEDED", "FAILED", "INTERRUPTED").contains(status)) break;
            if (!Set.of("QUEUED", "RUNNING").contains(status)) throw new IOException("Unexpected series status: " + status);
            Thread.sleep(Math.max(1, Math.min(500, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
        }
        out.println(JSON.writeValueAsString(view));
        if (!"SUCCEEDED".equals(object(view.get("series")).get("status"))) return 1;
        if (!options.checkBaseline) return 0;
        URI regression = options.server.resolve("/run-series/" + seriesId + "/regression?tolerancePct=" + options.tolerancePct);
        Map<?, ?> check = options.baselineFile == null ? object(request(client, regression, null, null, Duration.ofSeconds(30)))
                : object(request(client, regression, HttpRequest.BodyPublishers.ofFile(options.baselineFile), "application/json", Duration.ofSeconds(30)));
        out.println(JSON.writeValueAsString(check));
        if (check.get("cautions") instanceof List<?> cautions) cautions.forEach(c -> err.println("Caution: " + c));
        return switch (String.valueOf(check.get("overall"))) {
            case "PASS" -> 0;
            case "REGRESSION" -> { err.println("Regression against baseline " + check.get("baselineSeriesId")); yield EXIT_REGRESSION; }
            default -> { err.println("Baseline check inconclusive: " + check.get("overall")); yield EXIT_INCONCLUSIVE; }
        };
    }

    private static int sweep(HttpClient client, Options options, String canonical, Path input, PrintStream out, PrintStream err) throws Exception {
        String grid = JSON.writeValueAsString(options.grid);
        URI submit = options.server.resolve("/run-sweeps?repetitions=" + (options.repeat == null ? 3 : options.repeat) + "&warmup=" + options.warmup);
        HttpRequest.BodyPublisher body;
        String contentType;
        if (input == null) {
            body = HttpRequest.BodyPublishers.ofString("{\"definition\":" + canonical + ",\"grid\":" + grid + "}", StandardCharsets.UTF_8);
            contentType = "application/json";
        } else {
            String boundary = "lab-" + UUID.randomUUID();
            contentType = "multipart/form-data; boundary=" + boundary;
            body = HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\nContent-Disposition: form-data; name=\"scenario\"; filename=\"scenario.json\"\r\nContent-Type: application/json\r\n\r\n" + canonical
                            + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"grid\"\r\nContent-Type: application/json\r\n\r\n" + grid
                            + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"input.xlsx\"\r\nContent-Type: application/octet-stream\r\n\r\n", StandardCharsets.UTF_8),
                    HttpRequest.BodyPublishers.ofFile(input),
                    HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
        }
        Map<?, ?> submission = object(request(client, submit, body, contentType, Duration.ofSeconds(30)));
        String sweepId = UUID.fromString((String)submission.get("sweepId")).toString();
        out.println(JSON.writeValueAsString(submission));
        if (!options.waitForCompletion) return 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(options.timeoutSeconds);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) { err.println("Wait timed out; sweep continues: " + sweepId); return 2; }
            Map<?, ?> view = object(request(client, options.server.resolve("/run-sweeps/" + sweepId), null, null,
                    Duration.ofNanos(Math.min(remaining, TimeUnit.SECONDS.toNanos(30)))));
            String status = (String)object(view.get("sweep")).get("status");
            if (Set.of("SUCCEEDED", "FAILED", "INTERRUPTED").contains(status)) {
                out.println(JSON.writeValueAsString(view));
                return status.equals("SUCCEEDED") ? 0 : 1;
            }
            if (!Set.of("QUEUED", "RUNNING").contains(status)) throw new IOException("Unexpected sweep status: " + status);
            Thread.sleep(Math.max(1, Math.min(500, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
        }
    }

    /** {@code name=1,2,4}: integers and true/false keep their type, anything else is a string. */
    static Map.Entry<String, List<Object>> gridEntry(String spec) {
        int equals = spec.indexOf('=');
        if (equals < 1 || equals == spec.length() - 1) throw new ScenarioException("--grid needs NAME=V1,V2");
        var values = new ArrayList<Object>();
        for (String raw : spec.substring(equals + 1).split(",", -1)) {
            String value = raw.strip();
            if (value.isEmpty()) throw new ScenarioException("--grid has an empty value: " + spec);
            values.add(value.matches("-?\\d{1,18}") ? (Object) Long.parseLong(value)
                    : value.equals("true") || value.equals("false") ? (Object) Boolean.parseBoolean(value) : value);
        }
        return Map.entry(spec.substring(0, equals).strip(), List.copyOf(values));
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
        return request(client, uri, body, type, timeout, body == null ? "GET" : "POST");
    }
    private static Object request(HttpClient client, URI uri, HttpRequest.BodyPublisher body, String type, Duration timeout, String method) throws Exception {
        var request = HttpRequest.newBuilder(uri).header("Accept", "application/json");
        if (body == null) request.GET();
        else { if (type != null) request.header("Content-Type", type); request.method(method, body); }
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

    private record Options(String command, Path file, String seriesId, URI server, boolean waitForCompletion, int timeoutSeconds,
            Integer repeat, int warmup, boolean checkBaseline, double tolerancePct, Map<String, List<Object>> grid,
            int olderThanDays, boolean apply, Path baselineFile) {
        static Options parse(String[] args) {
            if (args.length == 0 || !Set.of("run", "validate", "list", "baseline", "sweep", "retention").contains(args[0])) throw new ScenarioException(USAGE);
            String command = args[0]; int index = 1; Path file = null; String seriesId = null;
            if (!command.equals("list") && !command.equals("retention")) {
                if (args.length <= index || args[index].startsWith("--")) throw new ScenarioException(USAGE);
                if (command.equals("baseline")) seriesId = args[index++]; else file = Path.of(args[index++]);
            }
            URI server = URI.create("http://localhost:8080"); boolean wait = false; int timeout = 120;
            Integer repeat = null; int warmup = 0; boolean check = false; double tolerance = 5.0;
            var grid = new LinkedHashMap<String, List<Object>>();
            int olderThanDays = 0; boolean apply = false; Path baselineFile = null;
            Set<String> seen = new HashSet<>();
            while (index < args.length) {
                String key = args[index++];
                if (!key.equals("--grid") && !seen.add(key)) throw new ScenarioException("Duplicate option: " + key);
                boolean runs = command.equals("run") || command.equals("sweep");
                if (key.equals("--wait") && runs) { wait = true; continue; }
                if (key.equals("--check-baseline") && command.equals("run")) { check = true; continue; }
                if (key.equals("--apply") && command.equals("retention")) { apply = true; continue; }
                if (index >= args.length) throw new ScenarioException(USAGE);
                String value = args[index++];
                if (key.equals("--server") && !command.equals("validate")) server = URI.create(value);
                else if (key.equals("--timeout-seconds") && runs) timeout = Integer.parseInt(value);
                else if (key.equals("--repeat") && runs) repeat = Integer.parseInt(value);
                else if (key.equals("--warmup") && runs) warmup = Integer.parseInt(value);
                else if (key.equals("--older-than-days") && command.equals("retention")) olderThanDays = Integer.parseInt(value);
                else if (key.equals("--grid") && command.equals("sweep")) {
                    var entry = gridEntry(value);
                    if (grid.put(entry.getKey(), entry.getValue()) != null) throw new ScenarioException("Duplicate grid parameter: " + entry.getKey());
                }
                else if (key.equals("--tolerance-pct") && command.equals("run")) tolerance = Double.parseDouble(value);
                else if (key.equals("--check-baseline-file") && command.equals("run")) { baselineFile = Path.of(value); check = true; }
                else if (key.equals("--export") && command.equals("baseline")) baselineFile = Path.of(value);
                else throw new ScenarioException("Unsupported option: " + key);
            }
            if (seen.contains("--timeout-seconds") && !wait) throw new ScenarioException("--timeout-seconds requires --wait");
            if (timeout < 1 || timeout > 3600) throw new ScenarioException("timeout must be between 1 and 3600 seconds");
            if (seen.contains("--warmup") && repeat == null && !command.equals("sweep")) throw new ScenarioException("--warmup requires --repeat");
            if (command.equals("sweep") && grid.isEmpty()) throw new ScenarioException("sweep needs at least one --grid NAME=V1,V2");
            if (command.equals("retention") && (olderThanDays < 1 || olderThanDays > 3650))
                throw new ScenarioException("retention needs --older-than-days 1..3650");
            if (repeat != null && (repeat < 1 || repeat > 50 || warmup < 0 || warmup > 10))
                throw new ScenarioException("--repeat must be 1..50 and --warmup 0..10");
            if (seen.contains("--check-baseline") && seen.contains("--check-baseline-file"))
                throw new ScenarioException("Use either --check-baseline or --check-baseline-file");
            if (check && (repeat == null || !wait)) throw new ScenarioException("--check-baseline requires --repeat and --wait");
            if (seen.contains("--check-baseline-file") && !Files.isRegularFile(baselineFile))
                throw new ScenarioException("Baseline file not found: " + baselineFile);
            if (seen.contains("--tolerance-pct") && !check) throw new ScenarioException("--tolerance-pct requires --check-baseline");
            if (!(tolerance >= 0 && tolerance <= 100)) throw new ScenarioException("--tolerance-pct must be 0..100");
            if (!("http".equals(server.getScheme()) || "https".equals(server.getScheme())) || server.getHost() == null
                    || server.getUserInfo() != null || server.getQuery() != null || server.getFragment() != null
                    || !(server.getPath().isEmpty() || server.getPath().equals("/"))) throw new ScenarioException("--server requires an HTTP(S) origin URL");
            return new Options(command, file, seriesId, server, wait, timeout, repeat, warmup, check, tolerance, grid, olderThanDays, apply, baselineFile);
        }
    }
}
