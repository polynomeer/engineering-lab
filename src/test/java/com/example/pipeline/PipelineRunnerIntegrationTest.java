package com.example.pipeline;

import com.polynomeer.excelpipeline.config.PipelineProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PipelineRunnerIntegrationTest {

    @Test
    void runsPipelineAndCollectsValidationErrors() throws Exception {
        PipelineProperties properties = new PipelineProperties();
        properties.getQueue().setRawCapacity(8);
        properties.getQueue().setMappedCapacity(8);
        properties.getThreads().setValidator(3);
        properties.getThreads().setInserter(2);
        properties.getBackpressure().setOfferTimeoutMs(200L);

        PipelineRunner runner = new PipelineRunner(properties);
        PipelineRunner.PipelineRunResult result = runner.runDummyPipeline();

        int expectedErrors = 13;
        int expectedInserted = PipelineRunner.DUMMY_ROW_COUNT - expectedErrors;

        assertEquals(PipelineRunner.DUMMY_ROW_COUNT, result.getProducedCount());
        assertEquals(expectedInserted, result.getInsertedCount());
        assertFalse(result.getValidationErrors().isEmpty());
        assertEquals(expectedErrors, result.getValidationErrors().size());
    }
}
