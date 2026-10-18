package com.pnomeer.lab.cli;

import com.pnomeer.lab.scenario.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LabCliTest {
    @TempDir Path root;
    private static final String SCENARIO = "experiments/concurrency/queue-contention/scenarios/small-buffer.yaml";
    @Test void rejectsInvalidArgumentsAndInputsWithoutNetworkAccess() throws Exception {
        assertEquals(1, invoke("unknown"));
        assertEquals(1, invoke("run", SCENARIO, "--timeout-seconds", "1"));
        assertEquals(1, invoke("run", SCENARIO, "--server", "file:///tmp/file"));
        assertEquals(1, invoke("validate", SCENARIO, "--wait"));
        Path input = root.resolve("input.xlsx"); Files.write(input, new byte[]{1,2,3});
        assertThrows(ScenarioException.class, () -> LabCli.verifyInput(root, new ScenarioDefinition.Input("input.xlsx", "0".repeat(64))));
        Path directory = Files.createDirectory(root.resolve("scenarios"));
        Files.createSymbolicLink(directory.resolve("escape.xlsx"), input);
        assertThrows(ScenarioException.class, () -> LabCli.verifyInput(directory,
                new ScenarioDefinition.Input("escape.xlsx", ScenarioDocuments.sha256(new byte[]{1,2,3}))));
    }
    @Test void parsesSweepGridsWithTypedValuesAndRejectsMalformedOnes() {
        assertEquals(java.util.Map.entry("queueCapacity", java.util.List.<Object>of(1L, 4L, 16L)), LabCli.gridEntry("queueCapacity=1,4,16"));
        assertEquals(java.util.List.<Object>of(true, "PIPELINE", -2L), LabCli.gridEntry("x=true, PIPELINE ,-2").getValue());
        assertThrows(ScenarioException.class, () -> LabCli.gridEntry("=1"));
        assertThrows(ScenarioException.class, () -> LabCli.gridEntry("name="));
        assertThrows(ScenarioException.class, () -> LabCli.gridEntry("name=1,,2"));
        assertEquals(1, invoke("sweep", SCENARIO)); // needs a grid
        assertEquals(1, invoke("sweep", SCENARIO, "--grid", "a=1", "--grid", "a=2")); // duplicate parameter
        assertEquals(1, invoke("sweep", SCENARIO, "--grid", "a=1", "--check-baseline")); // run-only option
    }
    @Test void reportsFailedRunsAndTimeoutWithoutResubmitting() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String id = UUID.randomUUID().toString();
        var status = new java.util.concurrent.atomic.AtomicReference<>("FAILED");
        var submissions = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/runs", exchange -> {
            String body;
            if (exchange.getRequestMethod().equals("POST")) {
                submissions.incrementAndGet(); exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
                body = "{\"runId\":\""+id+"\"}";
            } else body = "{\"status\":\""+status.get()+"\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        String origin = "http://127.0.0.1:"+server.getAddress().getPort();
        try {
            assertEquals(1, invoke("run",SCENARIO,"--server",origin,"--wait"));
            status.set("RUNNING");
            assertTimeoutPreemptively(Duration.ofSeconds(4), () -> assertEquals(2,
                    invoke("run",SCENARIO,"--server",origin,"--wait","--timeout-seconds","1")));
            assertEquals(2, submissions.get());
        } finally { server.stop(0); }
    }
    @Test void rejectsOversizedServerResponses() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/experiments", exchange -> {
            exchange.sendResponseHeaders(200, 1048577);
            try (var output = exchange.getResponseBody()) { output.write(new byte[1048577]); }
            catch (IOException ignored) { /* the bounded client cancels the response */ }
        });
        server.start();
        try { assertEquals(1, invoke("list", "--server", "http://127.0.0.1:"+server.getAddress().getPort())); }
        finally { server.stop(0); }
    }
    private int invoke(String... args) {
        return LabCli.execute(args, new PrintStream(OutputStream.nullOutputStream()), new PrintStream(OutputStream.nullOutputStream()));
    }
}
