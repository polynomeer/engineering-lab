package com.pnomeer.pipeline.experiment;

import com.pnomeer.lab.core.ExperimentScenario;
import com.pnomeer.pipeline.PipelineRunner;

import java.util.Map;
import java.util.function.Consumer;

public record PipelineExperimentScenario(
        byte[] payload,
        PipelineRunner.RunMode runMode,
        Consumer<PipelineRunner.ProgressSnapshot> progressListener,
        PipelineExperimentCapture capture) implements ExperimentScenario {

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
        return Map.of("mode", runMode.name(), "payloadBytes", String.valueOf(payload.length));
    }
}
