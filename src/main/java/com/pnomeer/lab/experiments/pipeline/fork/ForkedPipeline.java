package com.pnomeer.lab.experiments.pipeline.fork;

import com.pnomeer.lab.core.DeadLetter;
import com.pnomeer.lab.core.MetricPoint;
import com.pnomeer.lab.core.input.InputPayload;
import com.pnomeer.lab.experiments.pipeline.PipelineRunner;
import com.pnomeer.lab.experiments.pipeline.config.PipelineProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Runs one pipeline pass in a child JVM limited to {@code -Xmx<heapMiB>m}, so the sampled heap belongs to
 * this run alone. The child gets the same classpath and configuration and its own in-memory H2.
 */
public final class ForkedPipeline {
    public static final int MIN_HEAP_MIB = 32, MAX_HEAP_MIB = 8192;
    static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final int MAX_LINE_BYTES = 1 << 20, STDERR_TAIL_BYTES = 16 * 1024;
    private static final String LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Outcome(PipelineRunner.PipelineRunResult result, long heapPeakBytes, long heapMaxBytes, long gcMillis) { }

    private ForkedPipeline() { }

    public static Outcome run(InputPayload payload, PipelineRunner.RunMode mode, Map<String, String> configuration, int heapMiB,
            Consumer<MetricPoint> metrics, Consumer<DeadLetter> deadLetters) throws IOException, InterruptedException {
        if (heapMiB < MIN_HEAP_MIB || heapMiB > MAX_HEAP_MIB) throw new IllegalArgumentException("forkedHeapMiB must be 32..8192");
        Path workspace = Files.createTempDirectory("lab-forked-pipeline-");
        Process process = null;
        var io = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Path input = workspace.resolve("input.xlsx");
            try (var in = payload.openStream()) { Files.copy(in, input); }
            var command = new ArrayList<>(java(heapMiB));
            command.add(input.toString());
            command.add(mode.name());
            new TreeMap<>(configuration).forEach((k, v) -> command.add(k + "=" + v));
            var builder = new ProcessBuilder(command).directory(workspace.toFile());
            // Ambient JVM options would silently change the heap limit or flags being measured.
            List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").forEach(builder.environment()::remove);
            process = builder.start();
            process.getOutputStream().close();
            var child = process;
            Future<byte[]> stderr = io.submit(() -> tail(child.getErrorStream()));
            Future<JsonNode> result = io.submit(() -> read(child.getInputStream(), metrics, deadLetters));
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) throw new IOException("Forked pipeline exceeded " + TIMEOUT.toMinutes() + " minutes");
            String errors = new String(get(stderr), StandardCharsets.UTF_8);
            JsonNode outcome;
            boolean outOfMemory = errors.contains("OutOfMemoryError");
            try { outcome = get(result); }
            catch (OutOfMemory ex) { outcome = null; outOfMemory = true; }
            if (outOfMemory) throw new IOException("Forked JVM ran out of heap at -Xmx" + heapMiB + "m (OutOfMemoryError)");
            if (process.exitValue() != 0 || outcome == null)
                throw new IOException("Forked pipeline exited with " + process.exitValue() + lastLine(errors));
            return outcome(outcome);
        } finally {
            // Kill first: the readers only finish once the child's streams close.
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            io.shutdownNow();
            deleteQuietly(workspace);
        }
    }

    /** Same JVM binary and classpath as this server; boot jars start the child through Spring Boot's launcher. */
    static List<String> java(int heapMiB) {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx" + heapMiB + "m", "-Xms" + Math.min(32, heapMiB) + "m", "-XX:+ExitOnOutOfMemoryError",
                "-cp", absoluteClasspath(System.getProperty("java.class.path"))));
        var location = ForkedPipelineMain.class.getProtectionDomain().getCodeSource().getLocation().toString();
        if (location.startsWith("jar:") || location.contains("!/BOOT-INF/") || location.contains("!BOOT-INF")) {
            command.add("-Dloader.main=" + ForkedPipelineMain.class.getName());
            command.add(LAUNCHER);
        } else command.add(ForkedPipelineMain.class.getName());
        return command;
    }

    /** The child runs in its own workspace directory, so relative entries (java -jar build/libs/x.jar) must be resolved here. */
    static String absoluteClasspath(String classpath) {
        return String.join(File.pathSeparator, Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(entry -> !entry.isEmpty()).map(entry -> Path.of(entry).toAbsolutePath().normalize().toString()).toList());
    }

    private static JsonNode read(InputStream stdout, Consumer<MetricPoint> metrics, Consumer<DeadLetter> deadLetters) throws IOException {
        JsonNode result = null;
        try (var in = new BufferedInputStream(stdout)) {
            String line;
            while ((line = line(in)) != null) {
                if (line.isBlank()) continue;
                // -XX:+ExitOnOutOfMemoryError writes its "Terminating due to ..." notice to stdout.
                if (!line.startsWith("{") && line.contains("OutOfMemoryError")) throw new OutOfMemory();
                JsonNode node = JSON.readTree(line);
                switch (node.path("t").asString()) {
                    case "metric" -> metrics.accept(JSON.treeToValue(node.get("point"), MetricPoint.class));
                    case "deadLetter" -> {
                        var cells = new ArrayList<String>();
                        node.path("cells").forEach(c -> cells.add(c.isNull() ? null : c.asString()));
                        deadLetters.accept(new DeadLetter(node.path("row").asInt(), "validation", node.path("reason").asString(), cells));
                    }
                    case "result" -> result = node;
                    default -> throw new IOException("Unknown forked pipeline line");
                }
            }
        }
        return result;
    }

    /** The child stopped on its heap limit; distinguished from a protocol error. */
    static final class OutOfMemory extends IOException { OutOfMemory() { super("Forked JVM ran out of heap"); } }

    /** One UTF-8 line without its terminator, or null at end of stream; longer lines are a protocol error. */
    private static String line(InputStream in) throws IOException {
        var buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (buffer.size() >= MAX_LINE_BYTES) throw new IOException("Forked pipeline line exceeds 1 MiB");
            buffer.write(b);
        }
        return b == -1 && buffer.size() == 0 ? null : buffer.toString(StandardCharsets.UTF_8);
    }

    private static byte[] tail(InputStream stderr) throws IOException {
        var ring = new ArrayDeque<byte[]>();
        int size = 0;
        byte[] block = new byte[4096];
        int n;
        try (stderr) {
            while ((n = stderr.read(block)) != -1) {
                ring.add(Arrays.copyOf(block, n));
                size += n;
                while (size - ring.peek().length >= STDERR_TAIL_BYTES) size -= ring.poll().length;
            }
        }
        var out = new ByteArrayOutputStream(size);
        for (var part : ring) out.write(part);
        return out.toByteArray();
    }

    private static Outcome outcome(JsonNode node) {
        var reasons = new HashMap<String, Long>();
        node.path("reasons").properties().forEach(e -> reasons.put(e.getKey(), e.getValue().asLong()));
        var result = new PipelineRunner.PipelineRunResult(node.path("produced").asInt(), node.path("inserted").asInt(),
                node.path("retries").asInt(), List.of(), reasons, node.path("rawWaitNanos").asLong(), node.path("mappedWaitNanos").asLong(),
                node.path("rawEnqueued").asLong(), node.path("mappedEnqueued").asLong(), node.path("elapsedMs").asLong());
        return new Outcome(result, node.path("heapPeakBytes").asLong(), node.path("heapMaxBytes").asLong(), node.path("gcMs").asLong());
    }

    /** Inverse of {@link PipelineRunner#configurationSnapshot()}. */
    static PipelineProperties properties(Map<String, String> s) {
        var p = new PipelineProperties();
        p.setShutdownTimeoutMs(Long.parseLong(s.get("pipeline.shutdown-timeout-ms")));
        p.setErrorPreviewLimit(Integer.parseInt(s.get("pipeline.error-preview-limit")));
        p.getQueue().setRawCapacity(Integer.parseInt(s.get("pipeline.queue.raw-capacity")));
        p.getQueue().setMappedCapacity(Integer.parseInt(s.get("pipeline.queue.mapped-capacity")));
        p.getThreads().setValidator(Integer.parseInt(s.get("pipeline.threads.validator")));
        p.getThreads().setInserter(Integer.parseInt(s.get("pipeline.threads.inserter")));
        p.getInsert().setFlushIntervalMs(Long.parseLong(s.get("pipeline.insert.flush-interval-ms")));
        p.getInsert().setMaxAttempts(Integer.parseInt(s.get("pipeline.insert.max-attempts")));
        p.getInsert().setRetryBackoffMs(Long.parseLong(s.get("pipeline.insert.retry-backoff-ms")));
        p.getInsert().setChunkSize(Integer.parseInt(s.get("pipeline.insert.chunk-size")));
        p.getBackpressure().setOfferTimeoutMs(Long.parseLong(s.get("pipeline.backpressure.offer-timeout-ms")));
        return p;
    }

    private static <T> T get(Future<T> future) throws IOException, InterruptedException {
        try { return future.get(30, TimeUnit.SECONDS); }
        catch (ExecutionException ex) { throw ex.getCause() instanceof IOException io ? io : new IOException("Forked pipeline output failed", ex.getCause()); }
        catch (TimeoutException ex) { throw new IOException("Forked pipeline output did not close", ex); }
    }

    private static String lastLine(String errors) {
        var lines = errors.strip().lines().filter(l -> !l.isBlank()).toList();
        return lines.isEmpty() ? "" : ": " + lines.stream().filter(l -> !l.startsWith("\tat ")).reduce((a, b) -> b).orElse(lines.getLast());
    }

    private static void deleteQuietly(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
}
