package com.pnomeer.lab.worker;

import java.nio.file.*;

/** Isolated executable used only by process integration tests. */
public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--descendant")) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Thread.sleep(60000); } catch (InterruptedException ignored) {}
            }));
            Thread.sleep(60000); return;
        }
        if (args.length == 1 && args[0].equals("--probe")) {
            String probeMode = "ready";
            try (var input = ProcessFixture.class.getResourceAsStream("/probe-mode.txt")) {
                if (input != null) probeMode = new String(input.readNBytes(64), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (probeMode.equals("hang")) { Thread.sleep(60000); return; }
            if (probeMode.equals("oversize")) { System.out.write(new byte[8192]); return; }
            System.out.write(WireJson.encode(WireJson.object().put("protocolVersion", 1).put("moduleId", "fixture-module")
                    .put("javaMajor", Runtime.version().feature()).put("ready", !probeMode.equals("unavailable"))));
            return;
        }
        var command = WorkerProtocol.readCommand(System.in, ProtocolLimits.DEFAULT);
        Path workspace = Path.of(command.path("workspace").textValue());
        Files.writeString(workspace.resolve("pid"), Long.toString(ProcessHandle.current().pid()));
        ParentConnection.watch(System.in, () -> Runtime.getRuntime().halt(19));
        String mode = command.path("parameters").path("mode").textValue();
        if (mode.startsWith("descendant-")) {
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar", workspace.resolve("worker.jar").toString(), "--descendant")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Files.writeString(workspace.resolve("descendant.pid"), Long.toString(child.pid()));
            // Keep the relationship observable before exit; detached-before-observation is out of scope.
            Thread.sleep(500);
            if (mode.equals("descendant-timeout")) { Thread.sleep(60000); return; }
        }
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
        if (mode.equals("report") || mode.equals("bad-report")) {
            Path report = Files.writeString(workspace.resolve("report.json"), "{\"count\":2}");
            writer.emit("ARTIFACT", WireJson.object().put("path", "report.json").put("sizeBytes", Files.size(report))
                    .put("sha256", mode.equals("bad-report") ? "c".repeat(64) : WorkerManifest.sha256(report)).put("kind", "REPORT"));
        }
        if (mode.equals("artifact")) writer.emit("ARTIFACT", WireJson.object().put("path", "unverified.json")
                .put("sizeBytes", 0).put("sha256", "c".repeat(64)).put("kind", "REPORT"));
        writer.result((mode.equals("failed") || mode.equals("evidence-failed")) ? "FAILED" : "SUCCEEDED", "", WireJson.object());
        if (mode.equals("nonzero")) System.exit(7);
    }
}
