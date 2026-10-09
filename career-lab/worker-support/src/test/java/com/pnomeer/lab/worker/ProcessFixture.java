package com.pnomeer.lab.worker;

import java.nio.file.*;

/** Isolated executable used only by process integration tests. */
public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        var command = WorkerProtocol.readCommand(System.in, ProtocolLimits.DEFAULT);
        Path workspace = Path.of(command.path("workspace").textValue());
        Files.writeString(workspace.resolve("pid"), Long.toString(ProcessHandle.current().pid()));
        ParentConnection.watch(System.in, () -> Runtime.getRuntime().halt(19));
        String mode = command.path("parameters").path("mode").textValue();
        if (mode.equals("stubborn")) Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Thread.sleep(60000); } catch (InterruptedException ignored) {}
        }));
        if (mode.equals("hang") || mode.equals("stubborn")) { Thread.sleep(60000); return; }
        if (mode.equals("missing")) return;
        if (mode.equals("malformed")) { System.out.println("{broken"); return; }
        if (mode.equals("oversize")) { System.out.print("x".repeat(65537)); System.out.flush(); return; }
        if (mode.equals("stderr")) {
            byte[] bytes = new byte[8192]; java.util.Arrays.fill(bytes, (byte)'x');
            for (int i=0; i<256; i++) System.err.write(bytes);
            System.err.flush();
        }
        String sha = WorkerManifest.sha256(workspace.resolve("worker.jar"));
        var identity = new WorkerProtocol.Identity(command.path("runId").textValue(), "fixture-module", "fixture.sequence", command.path("definitionSha256").textValue(), sha);
        var writer = new WorkerProtocol.Writer(System.out, identity, ProtocolLimits.DEFAULT);
        var env = WireJson.object().put("javaVersion", System.getProperty("java.version")).put("javaVendor", "fixture")
                .put("vmName", "fixture").put("osName", "fixture").put("osVersion", "fixture").put("osArch", "fixture")
                .put("availableProcessors", 1).put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        var hello = WireJson.object().put("moduleId", "fixture-module").put("experimentId", "fixture.sequence")
                .put("definitionSha256", identity.definitionSha256()).put("artifactSha256", sha);
        hello.set("environment", env); hello.set("git", WireJson.object().putNull("commit").putNull("dirty").put("error", "fixture"));
        writer.emit("HELLO", hello);
        for (String phase : new String[]{"PREPARING", "MEASURING", "FINALIZING"}) {
            writer.emit("PHASE", WireJson.object().put("phase", phase));
            if (phase.equals("MEASURING") && (mode.equals("evidence") || mode.equals("evidence-failed"))) {
                var metric = WireJson.object().put("timestampEpochMs", 1);
                metric.set("gauges", WireJson.object()); metric.set("counters", WireJson.object().put("fixture.completed", 2)); metric.set("labels", WireJson.object());
                writer.emit("METRIC", metric);
                writer.emit("CHECK", WireJson.object().put("checkId", "unique").put("status", "PASS")
                        .put("expected", "2").put("observed", "2").put("description", "Synthetic integration check"));
            }
        }
        if (mode.equals("artifact")) writer.emit("ARTIFACT", WireJson.object().put("path", "unverified.json")
                .put("sizeBytes", 0).put("sha256", "c".repeat(64)).put("kind", "REPORT"));
        writer.result((mode.equals("failed") || mode.equals("evidence-failed")) ? "FAILED" : "SUCCEEDED", "", WireJson.object());
        if (mode.equals("nonzero")) System.exit(7);
    }
}
