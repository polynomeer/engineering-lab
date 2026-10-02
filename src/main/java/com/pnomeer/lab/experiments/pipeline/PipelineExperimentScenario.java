package com.pnomeer.lab.experiments.pipeline;

import com.pnomeer.lab.core.ExperimentScenario;
import com.pnomeer.lab.metrics.ExecutionMetricsSnapshot;

import java.util.Map;
import java.util.function.Consumer;

public record PipelineExperimentScenario(
        byte[] payload,
        PipelineRunner.RunMode runMode,
        Consumer<ExecutionMetricsSnapshot> progressListener,
        PipelineExperimentCapture capture,
        String fileName) implements ExperimentScenario {

    public PipelineExperimentScenario(byte[] payload, PipelineRunner.RunMode runMode,
            Consumer<ExecutionMetricsSnapshot> progressListener, PipelineExperimentCapture capture) {
        this(payload, runMode, progressListener, capture, "unknown.xlsx");
    }

    @Override
    public String scenarioId() {
        return runMode.name();
    }

    @Override
    public String displayName() {
        return "excel-" + runMode.name().toLowerCase();
    }

    @Override
    public Map<String, String> parameters() {
        try {
            String checksum = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(payload));
            return Map.of("mode", runMode.name(), "payloadBytes", String.valueOf(payload.length),
                    "input.sha256", checksum, "input.fileName", fileName);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
