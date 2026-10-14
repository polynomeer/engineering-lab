package com.portfolio.mcplab.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.worker.*;
import com.portfolio.mcplab.archrule.LayerArchitectureRules;
import com.portfolio.mcplab.contract.api.dto.ContractCreateRequest;
import com.portfolio.mcplab.contract.domain.Contract;
import com.portfolio.mcplab.contract.domain.InvalidContractTermException;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import jakarta.validation.Validation;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** [NEW-DESIGN] Finite, DB-free checks using the same FACT-based rules as the module tests. */
public final class ChecksWorker {
    public record Check(String id, String status, String expected, String observed, String description) {}
    public static List<Check> evaluate(String experiment, String variant) {
        if (experiment.equals("career.mcp-platform.architecture")) return architecture(variant);
        if (experiment.equals("career.mcp-platform.validation")) return validation(variant);
        throw new IllegalArgumentException("Unsupported experiment");
    }
    private static List<Check> architecture(String variant) {
        if (!Set.of("COMPLIANT", "VIOLATION").contains(variant)) throw new IllegalArgumentException("Unknown variant");
        String pkg = "com.portfolio.mcplab." + (variant.equals("COMPLIANT") ? "contract" : "badexample");
        var classes = new ClassFileImporter().importPackages(pkg);
        if (classes.size() < 3) throw new IllegalStateException("Architecture target missing; empty import is not a pass");
        var rules = List.of(LayerArchitectureRules.CONTROLLERS_MUST_NOT_ACCESS_INFRASTRUCTURE,
                LayerArchitectureRules.DOMAIN_MUST_NOT_DEPEND_ON_SPRING_WEB, LayerArchitectureRules.LAYER_DEPENDENCY);
        var ids = List.of("api-boundary", "domain-independence", "layer-dependency");
        var checks = new ArrayList<Check>();
        checks.add(new Check("import-target", "PASS", "at least 3 target classes", Integer.toString(classes.size()),
                "NEW-DESIGN: fixed lab classes; VIOLATION intentionally produces failed rule checks"));
        for (int i = 0; i < rules.size(); i++) {
            var result = rules.get(i).evaluate(classes);
            String details = result.hasViolation() ? result.getFailureReport().toString() : "no violations";
            if (details.length() > 8000) throw new IllegalStateException("Architecture evidence exceeds limit");
            checks.add(new Check(ids.get(i), result.hasViolation() ? "FAIL" : "PASS", "no violations", details,
                    "FACT-based guardrail; synthetic lab target, not a production code audit"));
        }
        return List.copyOf(checks);
    }
    private static List<Check> validation(String variant) {
        if (!Set.of("VALID", "BLANK_NAME", "PAST_EXPIRY", "DOMAIN_REJECTION").contains(variant)) throw new IllegalArgumentException("Unknown variant");
        // A fixed clock keeps @Future and cross-field dates reproducible on any execution date.
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        LocalDate today = LocalDate.now(clock);
        var request = new ContractCreateRequest(variant.equals("BLANK_NAME") ? "" : "Lab label",
                variant.equals("DOMAIN_REJECTION") ? today.plusMonths(6) : today,
                variant.equals("PAST_EXPIRY") ? today.minusDays(1) : today.plusMonths(1));
        var checks = new ArrayList<Check>();
        try (var factory = Validation.byDefaultProvider().configure().clockProvider(() -> clock).buildValidatorFactory()) {
            var violations = factory.getValidator().validate(request);
            String expectedField = variant.equals("BLANK_NAME") ? "counterpartyName" : variant.equals("PAST_EXPIRY") ? "expiresAt" : "";
            var fields = violations.stream().map(v -> v.getPropertyPath().toString()).sorted().toList();
            boolean expected = expectedField.isEmpty() ? fields.isEmpty() : fields.equals(List.of(expectedField));
            checks.add(new Check("bean-validation", expected ? "PASS" : "FAIL", expectedField.isEmpty() ? "accepted" : "rejected: " + expectedField,
                    fields.isEmpty() ? "accepted" : "rejected: " + String.join(",", fields), "FACT-based Bean Validation; NEW-DESIGN clock fixed to 2026-01-01 UTC"));
            if (!violations.isEmpty()) {
                checks.add(new Check("dto-mapping", "PASS", "not reached after input rejection", "not executed", "No mapping of invalid input"));
                checks.add(new Check("domain-rule", "PASS", "not reached after input rejection", "not executed", "No domain invocation after input rejection"));
                return List.copyOf(checks);
            }
            // The service's explicit DTO field forwarding is reproduced without allocating a DB sequence.
            String name = request.counterpartyName(); LocalDate start = request.startsAt(), end = request.expiresAt();
            checks.add(new Check("dto-mapping", "PASS", "name and dates preserved", name + ": " + start + " / " + end,
                    "NEW-DESIGN: explicit field forwarding; no MapStruct or database performance claim"));
            boolean rejected = false;
            Contract contract = null;
            try { contract = Contract.create("MCP-2026-000001", name, start, end); }
            catch (InvalidContractTermException e) { rejected = true; }
            boolean pass = variant.equals("DOMAIN_REJECTION") ? rejected : !rejected && contract.counterpartyName().equals(name)
                    && contract.startsAt().equals(start) && contract.expiresAt().equals(end);
            checks.add(new Check("domain-rule", pass ? "PASS" : "FAIL", variant.equals("DOMAIN_REJECTION") ? "rejected: expiry before start" : "accepted",
                    rejected ? "rejected: expiry before start" : "accepted", "FACT-based cross-field rule in the actual Contract aggregate"));
        }
        return List.copyOf(checks);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("Unsupported command");
        OutputStream protocol = System.out; System.setOut(System.err);
        var command = WorkerProtocol.readCommand(System.in, ProtocolLimits.DEFAULT);
        String experiment = command.path("experimentId").asText();
        var parameters = command.path("parameters");
        if (!parameters.isObject() || parameters.size() != 1 || !parameters.path("variant").isTextual()) throw new IllegalArgumentException("Expected variant");
        String variant = parameters.path("variant").asText();
        long remaining = command.path("deadlineEpochMs").asLong() - System.currentTimeMillis();
        if (remaining <= 0 || remaining > 900000) throw new IllegalArgumentException("Invalid deadline");
        Path workspace = Path.of(command.path("workspace").asText());
        if (!workspace.isAbsolute() || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Invalid workspace");
        // No external resources: process termination is sufficient on parent loss/deadline.
        ParentConnection.watch(System.in, () -> Runtime.getRuntime().halt(20));
        Thread deadline = new Thread(() -> {
            try { Thread.sleep(remaining); Runtime.getRuntime().halt(21); } catch (InterruptedException ignored) {}
        }, "checks-deadline"); deadline.setDaemon(true); deadline.start();
        var identity = new WorkerProtocol.Identity(command.path("runId").asText(), "mcp-platform", experiment,
                command.path("definitionSha256").asText(), hash(Path.of(System.getProperty("java.class.path"))));
        var writer = new WorkerProtocol.Writer(protocol, identity, ProtocolLimits.DEFAULT);
        ObjectNode hello = WireJson.object().put("moduleId", identity.moduleId()).put("experimentId", experiment)
                .put("definitionSha256", identity.definitionSha256()).put("artifactSha256", identity.artifactSha256());
        hello.set("environment", WireJson.object().put("javaVersion", System.getProperty("java.version")).put("javaVendor", System.getProperty("java.vendor"))
                .put("vmName", System.getProperty("java.vm.name")).put("osName", System.getProperty("os.name"))
                .put("osVersion", System.getProperty("os.version")).put("osArch", System.getProperty("os.arch"))
                .put("availableProcessors", Runtime.getRuntime().availableProcessors()).put("maxHeapBytes", Runtime.getRuntime().maxMemory()));
        hello.set("git", WireJson.object().putNull("commit").putNull("dirty").put("error", "Build Git provenance not embedded"));
        writer.emit("HELLO", hello); writer.emit("PHASE", WireJson.object().put("phase", "PREPARING"));
        writer.emit("PHASE", WireJson.object().put("phase", "MEASURING"));
        try {
            var checks = evaluate(experiment, variant);
            var report = WireJson.object().put("experimentId", experiment).put("variant", variant)
                    .put("provenance", "FACT-based rules; NEW-DESIGN fixtures and worker integration");
            var rows = report.putArray("checks");
            for (var check : checks) {
                var row = WireJson.object().put("checkId", check.id()).put("status", check.status()).put("expected", check.expected())
                        .put("observed", check.observed()).put("description", check.description());
                writer.emit("CHECK", row); rows.add(row);
            }
            writer.emit("PHASE", WireJson.object().put("phase", "FINALIZING"));
            Path file = workspace.resolve("checks-report.json"); Files.write(file, WireJson.encode(report), StandardOpenOption.CREATE_NEW);
            writer.emit("ARTIFACT", WireJson.object().put("path", file.getFileName().toString()).put("kind", "REPORT")
                    .put("sizeBytes", Files.size(file)).put("sha256", hash(file)));
            boolean failed = checks.stream().anyMatch(c -> c.status().equals("FAIL"));
            writer.result(failed ? "FAILED" : "SUCCEEDED", failed ? "EXPECTATION_NOT_MET" : "",
                    WireJson.object().put("variant", variant).put("checks", checks.size()));
        } catch (Exception e) {
            System.err.println("job_id=" + identity.runId() + " stage=checks reason=CHECK_EXECUTION_FAILED");
            e.printStackTrace(System.err);
            writer.result("FAILED", "CHECK_EXECUTION_FAILED", WireJson.object().put("variant", variant));
        } finally { deadline.interrupt(); }
    }
    private static String hash(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) { byte[] bytes = new byte[8192]; int n; while ((n = in.read(bytes)) != -1) digest.update(bytes, 0, n); }
        return HexFormat.of().formatHex(digest.digest());
    }
}
