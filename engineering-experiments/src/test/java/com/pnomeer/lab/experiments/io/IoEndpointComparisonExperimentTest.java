package com.pnomeer.lab.experiments.io;

import com.pnomeer.lab.core.ExperimentSummary;
import com.pnomeer.lab.core.MetricPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoEndpointComparisonExperimentTest {
    @Test
    void collectsLatencyAndCompletionMetrics() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            IoEndpointComparisonExperiment experiment = new IoEndpointComparisonExperiment(executor);
            List<MetricPoint> timeline = new ArrayList<>();

            ExperimentSummary summary = experiment.execute(
                    new IoEndpointComparisonScenario("io-test", 4, 1L, false, 1L),
                    timeline::add);

            assertEquals(4L, summary.counters().get("requests.virtualCompleted"));
            assertEquals(4L, summary.counters().get("requests.reactiveCompleted"));
            assertEquals(8L, summary.counters().get("requests.totalCompleted"));
            assertTrue(summary.gauges().get("virtual.avgLatencyMs") >= 0.0d);
            assertTrue(summary.gauges().get("reactive.avgLatencyMs") >= 0.0d);
            assertTrue(summary.gauges().get("throughput.totalRequestsPerSec") > 0.0d);
            assertTrue(summary.details().containsKey("winner"));
            assertFalse(timeline.isEmpty());
        }
    }
}
