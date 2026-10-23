package com.pnomeer.lab.experiments.pipeline.fork;

import com.pnomeer.lab.app.ingest.PipelineMetricPointMapper;
import com.pnomeer.lab.experiments.pipeline.PipelineRunner;
import com.pnomeer.lab.metrics.HeapSampler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Child-JVM entry point: runs one pipeline pass against its own in-memory H2 and reports on stdout as JSON
 * lines (metric, deadLetter, result). Everything else, logging included, goes to stderr.
 * Arguments: input path, run mode, then the parent's {@code configurationSnapshot()} as key=value pairs.
 */
public final class ForkedPipelineMain {
    private ForkedPipelineMain() { }

    public static void main(String[] args) throws Exception {
        PrintStream protocol = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, StandardCharsets.UTF_8);
        System.setOut(System.err); // keep library output off the protocol stream
        quietLogging();
        var json = JsonMapper.builder().build();
        Path input = Path.of(args[0]);
        var mode = PipelineRunner.RunMode.valueOf(args[1]);
        var snapshot = new HashMap<String, String>();
        for (int i = 2; i < args.length; i++) {
            int split = args[i].indexOf('=');
            snapshot.put(args[i].substring(0, split), args[i].substring(split + 1));
        }
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:forked-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(10); // Spring Boot's default pool, as in the server JVM
        int exit = 0;
        try (var dataSource = new HikariDataSource(config)) {
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.execute(new String(ForkedPipelineMain.class.getResourceAsStream("/schema.sql").readAllBytes(), StandardCharsets.UTF_8));
            var runner = new PipelineRunner(ForkedPipeline.properties(snapshot), jdbc,
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                    Long.parseLong(snapshot.getOrDefault("pipeline.insert.delay-ms", "0")));
            PipelineRunner.PipelineRunResult result;
            var heap = HeapSampler.start();
            try (heap; var stream = Files.newInputStream(input)) {
                result = runner.run(stream, mode,
                        progress -> emit(protocol, json, Map.of("t", "metric", "point", PipelineMetricPointMapper.fromProgressSnapshot(progress))),
                        error -> emit(protocol, json, Map.of("t", "deadLetter", "row", error.getRowIndex(), "reason", error.getReason(),
                                "cells", error.getRawSnapshot().getCells())));
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("t", "result");
            out.put("produced", result.getProducedCount());
            out.put("inserted", result.getInsertedCount());
            out.put("retries", result.getRetryCount());
            out.put("reasons", result.getValidationReasons());
            out.put("rawWaitNanos", result.getRawChannelEnqueueWaitNanos());
            out.put("mappedWaitNanos", result.getMappedChannelEnqueueWaitNanos());
            out.put("rawEnqueued", result.getRawChannelEnqueuedCount());
            out.put("mappedEnqueued", result.getMappedChannelEnqueuedCount());
            out.put("elapsedMs", result.getElapsedMillis());
            out.put("heapPeakBytes", heap.peakBytes());
            out.put("heapMaxBytes", heap.maxBytes());
            out.put("gcMs", heap.gcMillis());
            emit(protocol, json, out);
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            exit = 1;
        }
        protocol.flush();
        System.exit(exit);
    }

    private static synchronized void emit(PrintStream protocol, JsonMapper json, Map<String, ?> line) {
        protocol.println(json.writeValueAsString(line));
        if (protocol.checkError()) throw new IllegalStateException("Parent closed the protocol stream");
    }

    private static void quietLogging() {
        if (org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) instanceof ch.qos.logback.classic.Logger root)
            root.setLevel(ch.qos.logback.classic.Level.WARN);
    }
}
