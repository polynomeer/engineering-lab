package com.portfolio.mcplab.worker;

import com.pnomeer.lab.worker.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChecksWorkerIntegrationTest {
    @TempDir Path workspaceRoot;
    @ParameterizedTest @CsvSource({"architecture,COMPLIANT,SUCCEEDED,4", "architecture,VIOLATION,FAILED,4",
            "validation,VALID,SUCCEEDED,3", "validation,BLANK_NAME,SUCCEEDED,3",
            "validation,PAST_EXPIRY,SUCCEEDED,3", "validation,DOMAIN_REJECTION,SUCCEEDED,3"})
    void packagedWorkerChecksRealClassesAndDtos(String scenario, String variant, String status, int count) throws Exception {
        String experiment = "career.mcp-platform." + scenario;
        Path jar = Path.of(System.getProperty("checks.worker.jar"));
        String hash;
        try (var input = Files.newInputStream(jar)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[8192]; int n;
            while ((n=input.read(bytes))!=-1) digest.update(bytes,0,n); hash = HexFormat.of().formatHex(digest.digest());
        }
        String runId = UUID.randomUUID().toString();
        var manifest = new WorkerManifest(Path.of(System.getProperty("java.home"), "bin", "java"), jar, hash,
                "mcp-platform", Set.of(experiment), 128);
        var command = WireJson.object().put("protocolVersion", 1).put("runId", runId).put("experimentId", experiment)
                .put("definitionSha256", "a".repeat(64)).put("deadlineEpochMs", System.currentTimeMillis()+300000).put("seed", 42).put("workspace", workspaceRoot.toString());
        command.set("parameters", WireJson.object().put("variant", variant));
        List<WorkerProtocol.Event> evidence = new ArrayList<>();
        var result = new WorkerProcess(new WorkerProcess.Limits(Duration.ofMinutes(5), Duration.ofSeconds(5), 1048576, ProtocolLimits.DEFAULT))
                .execute(manifest, command, workspaceRoot, new WorkerProcess.EvidenceSink() {
                    public void persist(WorkerProtocol.Event event, Path workspace) throws Exception {
                        assertTrue(evidence.size() < 16); evidence.add(event);
                        if (event.type().equals("ARTIFACT")) {
                            Path report = workspace.resolve(event.payload().path("path").textValue());
                            assertEquals(Files.size(report), event.payload().path("sizeBytes").longValue());
                            assertEquals(variant, WireJson.parse(Files.readAllBytes(report)).path("variant").textValue());
                        }
                    }
                    public void flush() {}
                });
        assertEquals(status, result.outcome().path("status").asText(), new String(result.stderr(), java.nio.charset.StandardCharsets.UTF_8));
        var checks = evidence.stream().filter(e -> e.type().equals("CHECK")).toList();
        assertEquals(count, checks.size());
        assertEquals(variant.equals("VIOLATION") ? 3 : 0, checks.stream().filter(e -> e.payload().path("status").asText().equals("FAIL")).count());
        assertEquals(0, evidence.stream().filter(e -> e.type().equals("METRIC")).count());
        assertEquals(1, evidence.stream().filter(e -> e.type().equals("ARTIFACT")).count());
        if (variant.equals("BLANK_NAME") || variant.equals("PAST_EXPIRY")) {
            assertEquals("not executed", checks.get(1).payload().path("observed").asText());
            assertEquals("not executed", checks.get(2).payload().path("observed").asText());
        }
    }
}
