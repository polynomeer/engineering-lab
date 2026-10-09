package com.portfolio.mcplab.sequence;

import com.pnomeer.lab.worker.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.DockerClientFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SequenceWorkerIntegrationTest {
    @TempDir Path workspaceRoot;
    @ParameterizedTest @ValueSource(strings={"BEFORE", "ATOMIC_UPDATE", "SELECT_FOR_UPDATE"})
    void packagedWorkerUsesIsolatedMysqlAndCleansItBeforeResult(String variant) throws Exception {
        Path jar = Path.of(System.getProperty("sequence.worker.jar"));
        String hash;
        try (var input = Files.newInputStream(jar)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[8192]; int n;
            while ((n=input.read(bytes))!=-1) digest.update(bytes,0,n); hash = HexFormat.of().formatHex(digest.digest());
        }
        String runId = UUID.randomUUID().toString();
        var manifest = new WorkerManifest(Path.of(System.getProperty("java.home"), "bin", "java"), jar, hash,
                "mcp-platform", Set.of("career.mcp-platform.sequence"), 256);
        var command = WireJson.object().put("protocolVersion", 1).put("runId", runId).put("experimentId", "career.mcp-platform.sequence")
                .put("definitionSha256", "a".repeat(64)).put("deadlineEpochMs", System.currentTimeMillis()+120000).put("seed", 42).put("workspace", workspaceRoot.toString());
        command.set("parameters", WireJson.object().put("variant", variant).put("workers", 10).put("blockSize", 1000));
        List<WorkerProtocol.Event> evidence = new ArrayList<>();
        var result = new WorkerProcess(new WorkerProcess.Limits(Duration.ofMinutes(2), Duration.ofSeconds(5), 1048576, ProtocolLimits.DEFAULT))
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
        assertEquals("SUCCEEDED", result.outcome().path("status").textValue(), new String(result.stderr(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(2, evidence.stream().filter(e -> e.type().equals("CHECK") && e.payload().path("status").asText().equals("PASS")).count());
        long overlaps = result.outcome().path("summary").path("sequence.overlapPairs").longValue();
        assertEquals(variant.equals("BEFORE"), overlaps > 0);
        assertEquals(1, evidence.stream().filter(e -> e.type().equals("ARTIFACT")).count());
        var containers = DockerClientFactory.instance().client().listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of("engineering-lab.run-id", runId)).exec();
        assertTrue(containers.isEmpty(), "Run-owned MySQL container must be removed before RESULT");
    }
}
