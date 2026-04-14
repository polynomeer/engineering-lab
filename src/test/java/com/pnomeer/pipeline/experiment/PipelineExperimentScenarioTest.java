package com.pnomeer.pipeline.experiment;

import com.pnomeer.pipeline.PipelineRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PipelineExperimentScenarioTest {
    @Test
    void exposesRunModeAsScenarioIdentity() {
        PipelineExperimentScenario scenario = new PipelineExperimentScenario(
                new byte[] {1, 2, 3},
                PipelineRunner.RunMode.SINGLE_THREAD,
                progress -> { },
                new PipelineExperimentCapture());

        assertEquals("SINGLE_THREAD", scenario.scenarioId());
        assertEquals("excel-single_thread", scenario.displayName());
        assertEquals("3", scenario.parameters().get("payloadBytes"));
    }
}
