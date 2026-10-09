package com.portfolio.mcplab.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.worker.*;
import com.portfolio.mcplab.McpLabApplication;
import com.portfolio.mcplab.sequence.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single isolated sequence experiment. stdout is protocol-only; application logs go to stderr. */
public final class SequenceWorker {
    public static void main(String[] args) throws Exception {
        OutputStream protocol = System.out;
        System.setOut(System.err);
        ObjectNode command = WorkerProtocol.readCommand(System.in, ProtocolLimits.DEFAULT);
        if (!"career.mcp-platform.sequence".equals(command.path("experimentId").textValue())) throw new IllegalArgumentException("Unsupported experiment");
        var parameters = command.path("parameters");
        if (!parameters.isObject() || parameters.size() != 3) throw new IllegalArgumentException("Expected variant, workers and blockSize");
        String variant = parameters.path("variant").asText();
        if (!Set.of("BEFORE", "ATOMIC_UPDATE", "SELECT_FOR_UPDATE").contains(variant)) throw new IllegalArgumentException("Unknown variant");
        int workers = integer(parameters.path("workers"), 2, 16), blockSize = integer(parameters.path("blockSize"), 1, 10000);
        long remaining = command.path("deadlineEpochMs").longValue() - System.currentTimeMillis();
        if (remaining <= 0 || remaining > 900000) throw new IllegalArgumentException("Invalid deadline");
        Path workspace = Path.of(command.path("workspace").textValue());
        if (!workspace.isAbsolute() || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Invalid workspace");
        Path jar = Path.of(System.getProperty("java.class.path"));
        var identity = new WorkerProtocol.Identity(command.path("runId").textValue(), "mcp-platform", "career.mcp-platform.sequence",
                command.path("definitionSha256").textValue(), hash(jar));
        var writer = new WorkerProtocol.Writer(protocol, identity, ProtocolLimits.DEFAULT);
        ObjectNode env = WireJson.object().put("javaVersion", System.getProperty("java.version")).put("javaVendor", System.getProperty("java.vendor"))
                .put("vmName", System.getProperty("java.vm.name")).put("osName", System.getProperty("os.name"))
                .put("osVersion", System.getProperty("os.version")).put("osArch", System.getProperty("os.arch"))
                .put("availableProcessors", Runtime.getRuntime().availableProcessors()).put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        ObjectNode hello = WireJson.object().put("moduleId", identity.moduleId()).put("experimentId", identity.experimentId())
                .put("definitionSha256", identity.definitionSha256()).put("artifactSha256", identity.artifactSha256());
        hello.set("environment", env); hello.set("git", WireJson.object().putNull("commit").putNull("dirty").put("error", "Build Git provenance not embedded"));
        writer.emit("HELLO", hello); writer.emit("PHASE", WireJson.object().put("phase", "PREPARING"));
        Thread main = Thread.currentThread(); AtomicBoolean finished = new AtomicBoolean();
        Runnable cancel = () -> {
            if (finished.get()) return;
            main.interrupt();
            Thread fallback = new Thread(() -> {
                try { Thread.sleep(30000); } catch (InterruptedException ignored) {}
                if (!finished.get()) Runtime.getRuntime().halt(20);
            }, "sequence-stop-deadline"); fallback.setDaemon(true); fallback.start();
        };
        ParentConnection.watch(System.in, cancel);
        Thread deadline = new Thread(() -> {
            try { Thread.sleep(remaining); cancel.run(); } catch (InterruptedException ignored) {}
        }, "sequence-deadline"); deadline.setDaemon(true); deadline.start();
        String status = "FAILED", reason = "PREPARATION_FAILED";
        ObjectNode summary = WireJson.object(); boolean measured = false, finalizing = false;
        // No reuse, external DB URL or user-selected image. Ryuk remains enabled for abnormal process loss.
        MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.39").withDatabaseName("sequence_lab")
                .withUsername("lab").withPassword("lab").withReuse(false)
                .withLabel("engineering-lab.run-id", identity.runId())
                .withUrlParam("connectTimeout", "5000").withUrlParam("socketTimeout", "5000");
        ConfigurableApplicationContext context = null;
        try {
            if (!DockerClientFactory.instance().isDockerAvailable()) throw new IllegalStateException("Docker unavailable");
            mysql.start();
            context = new SpringApplicationBuilder(McpLabApplication.class).web(WebApplicationType.NONE).run(
                    "--spring.main.banner-mode=off", "--spring.datasource.url=" + mysql.getJdbcUrl(),
                    "--spring.datasource.username=" + mysql.getUsername(), "--spring.datasource.password=" + mysql.getPassword(),
                    "--spring.datasource.hikari.maximum-pool-size=" + workers, "--spring.datasource.hikari.connection-timeout=5000");
            ContractCodeAllocator allocator = switch (variant) {
                case "BEFORE" -> context.getBean(NonAtomicContractCodeAllocator.class);
                case "ATOMIC_UPDATE" -> context.getBean(AtomicUpdateContractCodeAllocator.class);
                default -> context.getBean(SelectForUpdateContractCodeAllocator.class);
            };
            writer.emit("PHASE", WireJson.object().put("phase", "MEASURING")); measured = true;
            long start = System.nanoTime();
            var ranges = ConcurrentAllocationHarness.runConcurrently(allocator, "CONTRACT_CODE", blockSize, workers);
            long elapsed = System.nanoTime() - start;
            int overlaps = ConcurrentAllocationHarness.findOverlaps(ranges).size();
            long value = context.getBean(JdbcTemplate.class).queryForObject("SELECT current_value FROM contract_sequence WHERE seq_key='CONTRACT_CODE'", Long.class);
            boolean overlapExpected = variant.equals("BEFORE") ? overlaps > 0 : overlaps == 0;
            boolean coverage = value == (long) workers * blockSize;
            long next = 1;
            for (var range : ranges.stream().sorted(Comparator.comparingLong(AllocatedRange::rangeStart)).toList()) {
                coverage &= range.rangeStart() == next && range.size() == blockSize; next = range.rangeEnd() + 1;
            }
            boolean stateExpected = variant.equals("BEFORE") ? value < (long) workers * blockSize : coverage;
            ObjectNode metric = WireJson.object().put("timestampEpochMs", System.currentTimeMillis());
            metric.set("gauges", WireJson.object()); metric.set("labels", WireJson.object().put("variant", variant));
            metric.set("counters", WireJson.object().put("sequence.elapsedNanos", elapsed).put("sequence.overlapPairs", overlaps).put("sequence.finalValue", value));
            writer.emit("METRIC", metric);
            check(writer, "overlap-expectation", overlapExpected, variant.equals("BEFORE") ? "overlapPairs > 0" : "overlapPairs = 0", Integer.toString(overlaps));
            check(writer, "coverage-expectation", stateExpected, variant.equals("BEFORE") ? "lost update" : "gap-free coverage", Long.toString(value));
            ObjectNode report = WireJson.object().put("variant", variant).put("workers", workers).put("blockSize", blockSize).put("elapsedNanos", elapsed)
                    .put("overlapPairs", overlaps).put("finalValue", value);
            var array = report.putArray("ranges"); for (var range : ranges) array.addObject().put("start", range.rangeStart()).put("end", range.rangeEnd());
            Path file = workspace.resolve("sequence-report.json"); Files.write(file, WireJson.encode(report), StandardOpenOption.CREATE_NEW);
            writer.emit("PHASE", WireJson.object().put("phase", "FINALIZING")); finalizing = true;
            writer.emit("ARTIFACT", WireJson.object().put("path", file.getFileName().toString()).put("kind", "REPORT").put("sizeBytes", Files.size(file)).put("sha256", hash(file)));
            summary.put("sequence.overlapPairs", overlaps).put("sequence.finalValue", value).put("variant", variant);
            status = overlapExpected && stateExpected ? "SUCCEEDED" : "FAILED"; reason = status.equals("SUCCEEDED") ? "" : "EXPECTATION_NOT_MET";
        } catch (Exception e) {
            boolean interrupted = Thread.interrupted() || e instanceof InterruptedException;
            status = interrupted ? "INTERRUPTED" : "FAILED"; reason = interrupted ? "INTERRUPTED" : "EXECUTION_OR_PREPARATION_FAILED";
            System.err.println("job_id=" + identity.runId() + " stage=sequence reason=" + reason);
            e.printStackTrace(System.err);
        } finally {
            Thread.interrupted();
            try { if (measured && !finalizing) writer.emit("PHASE", WireJson.object().put("phase", "FINALIZING")); }
            catch (Exception e) { status = "FAILED"; reason = "PROTOCOL_FAILED"; }
            try { if (context != null) context.close(); }
            catch (Exception e) { status = "FAILED"; reason = "CONTEXT_CLEANUP_FAILED"; }
            try { mysql.stop(); }
            catch (Exception e) { status = "FAILED"; reason = "CONTAINER_CLEANUP_FAILED"; }
            finished.set(true); deadline.interrupt();
        }
        writer.result(status, reason, summary);
    }
    private static void check(WorkerProtocol.Writer writer, String id, boolean pass, String expected, String observed) throws IOException {
        writer.emit("CHECK", WireJson.object().put("checkId", id).put("status", pass ? "PASS" : "FAIL")
                .put("expected", expected).put("observed", observed).put("description", "Synthetic concurrent range allocation; not historical production performance"));
    }
    private static int integer(com.fasterxml.jackson.databind.JsonNode node, int min, int max) {
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < min || node.intValue() > max) throw new IllegalArgumentException("Parameter limit");
        return node.intValue();
    }
    private static String hash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) { byte[] bytes = new byte[8192]; int n; while ((n=input.read(bytes))!=-1) digest.update(bytes,0,n); }
        return HexFormat.of().formatHex(digest.digest());
    }
}
