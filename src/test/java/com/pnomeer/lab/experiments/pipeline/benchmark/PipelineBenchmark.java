package com.pnomeer.lab.experiments.pipeline.benchmark;

import com.pnomeer.lab.app.run.RunEvidenceController;
import com.pnomeer.lab.core.input.InputPayload;
import com.pnomeer.lab.core.storage.RunStatus;
import com.pnomeer.lab.experiments.pipeline.*;
import com.pnomeer.lab.experiments.pipeline.config.PipelineProperties;
import com.pnomeer.lab.runtime.*;
import com.pnomeer.lab.storage.*;
import com.zaxxer.hikari.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

/** Separate bounded-heap JVMs; fixture generation and evidence export are outside timing. */
public final class PipelineBenchmark {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record Trial(String runId, double wallMs, double inputRowsPerSecond, long sampledHeapPeakBytes,
            long maxHeapBytes, long gcMillis, int rawQueuePeak, int mappedQueuePeak,
            int inserted, long errors, int previewSize, String exportFile, String exportSha256) { }
    public record Case(String mode, int rows, int invalidEvery, long batchDelayMs, String inputSha256,
            int warmups, int repeats, List<Trial> trials) { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("case")) {
            measure(Path.of(args[1]), Path.of(args[2]), PipelineRunner.RunMode.valueOf(args[3]),
                    Integer.parseInt(args[4]), Integer.parseInt(args[5]), Long.parseLong(args[6]), Integer.parseInt(args[7]));
            return;
        }
        Path root = Path.of("build/reports/pipeline-benchmark/" + System.currentTimeMillis()).toAbsolutePath();
        Files.createDirectories(root);
        List<Case> results = new ArrayList<>();
        // Fixed ordering is recorded, not presented as randomized statistical inference.
        for (String profile : List.of("delayed", "native", "memory-small", "memory-large")) {
            int rows = profile.equals("memory-large") ? 100000 : profile.equals("memory-small") ? 10000 : 6000;
            int invalidEvery = profile.startsWith("memory") ? 10 : 0;
            long delay = profile.equals("delayed") ? 40 : 0;
            int repeats = profile.startsWith("memory") ? 1 : 3;
            Path input = root.resolve(profile + ".xlsx");
            fixture(input, rows, invalidEvery);
            for (var mode : PipelineRunner.RunMode.values()) {
                Case result = fork(root.resolve(profile + "-" + mode), input, mode, rows, invalidEvery, delay, repeats);
                results.add(result);
                System.out.printf(Locale.ROOT, "%s %s median=%.1fms peak=%.1fMiB%n", profile, mode,
                        median(result), result.trials().stream().mapToLong(Trial::sampledHeapPeakBytes).max().orElseThrow() / 1048576.0);
            }
        }
        Files.writeString(root.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        System.out.println("Benchmark evidence: " + root);
    }

    public static void fixture(Path file, int rows, int invalidEvery) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (var workbook = new SXSSFWorkbook(32)) {
            var sheet = workbook.createSheet("input");
            var header = sheet.createRow(0);
            for (int c = 0; c < 3; c++) header.createCell(c).setCellValue("col" + (c + 1));
            for (int i = 1; i <= rows; i++) {
                var row = sheet.createRow(i);
                row.createCell(0).setCellValue(invalidEvery > 0 && i % invalidEvery == 0 ? "" : "item-" + i);
                row.createCell(1).setCellValue("benchmark");
                row.createCell(2).setCellValue(i);
            }
            try (var out = Files.newOutputStream(file)) { workbook.write(out); }
        }
    }

    public static Case fork(Path output, Path input, PipelineRunner.RunMode mode, int rows,
            int invalidEvery, long delay, int repeats) throws Exception {
        Files.createDirectories(output);
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms32m", "-Xmx128m", "-cp", System.getProperty("lab.benchmark.classpath", System.getProperty("java.class.path")),
                PipelineBenchmark.class.getName(), "case", output.toAbsolutePath().toString(), input.toAbsolutePath().toString(),
                mode.name(), "" + rows, "" + invalidEvery, "" + delay, "" + repeats)
                .redirectErrorStream(true).redirectOutput(output.resolve("process.log").toFile()).start();
        try {
            if (!process.waitFor(180, TimeUnit.SECONDS)) throw new IllegalStateException("Benchmark timed out: " + output);
            if (process.exitValue() != 0) throw new IllegalStateException("Benchmark failed; inspect " + output.resolve("process.log"));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
        }
        return JSON.readValue(Files.readString(output.resolve("case.json")), Case.class);
    }

    public static double median(Case result) {
        return result.trials().stream().mapToDouble(Trial::wallMs).sorted().toArray()[result.trials().size() / 2];
    }

    private static void measure(Path output, Path input, PipelineRunner.RunMode mode, int rows,
            int invalidEvery, long delay, int repeats) throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:file:" + output.resolve("ingestion").toAbsolutePath() + ";CACHE_SIZE=8192");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        var properties = new PipelineProperties();
        properties.getQueue().setRawCapacity(64);
        properties.getQueue().setMappedCapacity(64);
        properties.getThreads().setValidator(2);
        properties.getThreads().setInserter(4);
        properties.getInsert().setChunkSize(100);
        properties.getBackpressure().setOfferTimeoutMs(100L);
        properties.setErrorPreviewLimit(16);
        var storage = new StorageProperties(output.toString(), 32, 16, 100, 3, 10, 30000, 100, 16, 1, ".", 2000);
        var git = new GitProvenanceProvider(Path.of("."), Duration.ofSeconds(2)).get();
        try (var dataSource = new HikariDataSource(config);
             var store = new JdbcRunStore(output.resolve("evidence"), 100, 16);
             var runner = new DurableExperimentRunner(store, () -> git, storage.writerOptions(), 16, 1)) {
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("create table ingest_item(id bigint generated by default as identity primary key, col1 varchar(255) not null, col2 varchar(255) not null, col3 integer not null)");
            var experiment = new PipelineExperiment(new PipelineRunner(properties, jdbc,
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)), delay));
            List<Trial> trials = new ArrayList<>();
            String checksum = null;
            // One full-input warmup per JVM, excluded from reported measurements.
            for (int iteration = -1; iteration < repeats; iteration++) {
                jdbc.execute("truncate table ingest_item");
                String id = UUID.randomUUID().toString();
                var capture = new PipelineExperimentCapture();
                var rawPeak = new AtomicInteger();
                var mappedPeak = new AtomicInteger();
                long gcStart = gcMillis();
                long elapsed;
                long heapPeak;
                try (var heap = new HeapSampler()) {
                    long start = System.nanoTime();
                    try (var payload = InputPayload.stage(Files.newInputStream(input), InputPayload.MAX_BYTES)) {
                        checksum = payload.sha256();
                        runner.run(experiment, new PipelineExperimentScenario(payload, mode, snapshot -> {
                            rawPeak.accumulateAndGet(snapshot.rawQueueSize(), Math::max);
                            mappedPeak.accumulateAndGet(snapshot.mappedQueueSize(), Math::max);
                        }, capture, input.getFileName().toString(), "benchmark"), id);
                    }
                    elapsed = System.nanoTime() - start;
                    heap.sample();
                    heapPeak = heap.peak.get();
                }
                long gcElapsed = gcMillis() - gcStart;
                var result = capture.getRunResult();
                long errors = invalidEvery == 0 ? 0 : rows / invalidEvery;
                long expectedSum = (long) rows * (rows + 1) / 2 - (long) invalidEvery * errors * (errors + 1) / 2;
                require(store.find(id).orElseThrow().status() == RunStatus.SUCCEEDED, "Run status");
                require(result != null && result.getProducedCount() == rows && result.getInsertedCount() == rows - errors
                        && result.getValidationErrorCount() == errors && result.getValidationErrors().size() == Math.min(16, errors), "Pipeline totals");
                require(jdbc.queryForObject("select count(*) from ingest_item", Long.class) == rows - errors
                        && jdbc.queryForObject("select count(distinct col3) from ingest_item", Long.class) == rows - errors
                        && jdbc.queryForObject("select sum(cast(col3 as bigint)) from ingest_item", Long.class) == expectedSum, "Persisted rows");
                int cursor = -1;
                long deadLetters = 0;
                while (true) {
                    var page = store.deadLetters(id, cursor, 100);
                    for (var row : page) { require(row.rowIndex() > cursor, "Dead-letter cursor"); cursor = row.rowIndex(); deadLetters++; }
                    if (page.size() < 100) break;
                }
                require(deadLetters == errors && rawPeak.get() <= 64 && mappedPeak.get() <= 64, "Bounded queues/dead letters");
                if (iteration >= 0) {
                    // Export only after timing/sampling, using the same format as the HTTP endpoint.
                    var response = new MockHttpServletResponse();
                    new RunEvidenceController(store, storage).export(id, response);
                    byte[] bytes = response.getContentAsByteArray();
                    String name = "trial-" + iteration + ".ndjson";
                    Files.write(output.resolve(name), bytes);
                    trials.add(new Trial(id, elapsed / 1e6, rows * 1e9 / elapsed, heapPeak,
                            Runtime.getRuntime().maxMemory(), gcElapsed, rawPeak.get(), mappedPeak.get(), result.getInsertedCount(),
                            errors, result.getValidationErrors().size(), name,
                            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
                }
            }
            Files.writeString(output.resolve("case.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                    new Case(mode.name(), rows, invalidEvery, delay, checksum, 1, repeats, trials)));
        }
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }
    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Benchmark verification failed: " + label);
    }
    private static final class HeapSampler implements AutoCloseable {
        final AtomicLong peak = new AtomicLong();
        final AtomicBoolean active = new AtomicBoolean(true);
        final Thread thread;
        HeapSampler() {
            sample();
            thread = Thread.ofPlatform().daemon().start(() -> {
                while (active.get()) {
                    sample();
                    try { Thread.sleep(5); } catch (InterruptedException ignored) { return; }
                }
            });
        }
        void sample() { peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max); }
        public void close() throws InterruptedException { active.set(false); thread.interrupt(); thread.join(); }
    }
}
