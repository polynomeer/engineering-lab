package com.portfolio.batchlab.worker;

import com.pnomeer.lab.worker.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryWorkerIntegrationTest {
    @TempDir Path workspaceRoot;
    @ParameterizedTest @CsvSource({"batch-memory,BEFORE", "excel-memory,AFTER"})
    void preparationFailureStillWritesValidPartialEvidence(String scenario, String variant) throws Exception {
        String experiment = "career.batch-excel." + scenario;
        Path jar = Path.of(System.getProperty("memory.worker.jar"));
        String hash;
        try (var input = Files.newInputStream(jar)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[8192]; int n;
            while ((n=input.read(bytes))!=-1) digest.update(bytes,0,n); hash = HexFormat.of().formatHex(digest.digest());
        }
        String runId = UUID.randomUUID().toString();
        var manifest = new WorkerManifest(Path.of(System.getProperty("java.home"), "bin", "java"), jar, hash,
                "batch-excel", Set.of(experiment), 128);
        var command = WireJson.object().put("protocolVersion", 1).put("runId", runId).put("experimentId", experiment)
                .put("definitionSha256", "a".repeat(64)).put("deadlineEpochMs", System.currentTimeMillis()+300000).put("seed", 42).put("workspace", workspaceRoot.toString());
        var parameters=WireJson.object().put("variant",variant).put("rows",10).put("seed",42);
        command.set("parameters",parameters);
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
        assertEquals("FAILED", result.outcome().path("status").asText(), new String(result.stderr(), java.nio.charset.StandardCharsets.UTF_8));
        var checks = evidence.stream().filter(e -> e.type().equals("CHECK")).toList();
        assertEquals(1, checks.size());
        assertEquals(1, checks.stream().filter(e -> e.payload().path("status").asText().equals("FAIL")).count());
        assertEquals(0, evidence.stream().filter(e -> e.type().equals("METRIC")).count());
        assertEquals(1, evidence.stream().filter(e -> e.type().equals("ARTIFACT")).count());
    }
}
