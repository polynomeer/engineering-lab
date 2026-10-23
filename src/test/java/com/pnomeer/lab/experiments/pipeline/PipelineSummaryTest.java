package com.pnomeer.lab.experiments.pipeline;

import com.pnomeer.lab.metrics.HeapSampler;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PipelineSummaryTest {
    private static PipelineRunner.PipelineRunResult result(int inserted, long elapsedMs) {
        return new PipelineRunner.PipelineRunResult(inserted, inserted, 0, List.of(), Map.of(), 0, 0, 0, 0, elapsedMs);
    }

    @Test void reportsThroughputOverThePipelineWindowAndTheSampledHeap() {
        var heap = HeapSampler.start();
        heap.close();
        var summary = PipelineExperiment.toSummary(result(500, 250), heap);
        assertEquals(2000.0, summary.gauges().get("throughput.insertedRowsPerSec"), 1e-9);
        assertEquals(heap.peakBytes(), summary.counters().get("heap.sampledPeakBytes"));
        assertTrue(summary.counters().get("gc.timeMs") >= 0);
    }

    @Test void omitsThroughputForAZeroLengthWindow() {
        var heap = HeapSampler.start();
        heap.close();
        assertFalse(PipelineExperiment.toSummary(result(10, 0), heap).gauges().containsKey("throughput.insertedRowsPerSec"));
    }
}
