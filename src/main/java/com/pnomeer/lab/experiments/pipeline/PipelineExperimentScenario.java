package com.pnomeer.lab.experiments.pipeline;

import com.pnomeer.lab.core.input.InputPayload;

import com.pnomeer.lab.core.ExperimentScenario;
import com.pnomeer.lab.metrics.ExecutionMetricsSnapshot;

import java.util.Map;
import java.util.function.Consumer;

public record PipelineExperimentScenario(
        InputPayload payload,
        PipelineRunner.RunMode runMode,
        Consumer<ExecutionMetricsSnapshot> progressListener,
        PipelineExperimentCapture capture,
        String fileName,
        String definitionScenarioId) implements ExperimentScenario {

    public PipelineExperimentScenario(byte[] payload, PipelineRunner.RunMode runMode,
            Consumer<ExecutionMetricsSnapshot> progressListener, PipelineExperimentCapture capture, String fileName, String definitionScenarioId) {
        this(InputPayload.memory(payload), runMode, progressListener, capture, fileName, definitionScenarioId);
    }

    public PipelineExperimentScenario(byte[] payload, PipelineRunner.RunMode runMode,
            Consumer<ExecutionMetricsSnapshot> progressListener, PipelineExperimentCapture capture, String fileName) {
        this(payload, runMode, progressListener, capture, fileName, runMode.name());
    }

    public PipelineExperimentScenario(byte[] payload, PipelineRunner.RunMode runMode,
            Consumer<ExecutionMetricsSnapshot> progressListener, PipelineExperimentCapture capture) {
        this(payload, runMode, progressListener, capture, "unknown.xlsx");
    }

    @Override
    public String scenarioId() {
        return definitionScenarioId;
    }

    @Override
    public String displayName() {
        return "excel-" + runMode.name().toLowerCase();
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("mode", runMode.name(), "payloadBytes", String.valueOf(payload.size()),
                "input.sha256", payload.sha256(), "input.fileName", fileName);
    }
}
