package com.pnomeer.lab.app.ingest;

import com.pnomeer.lab.metrics.ExecutionMetricsSnapshot;
import com.pnomeer.lab.core.MetricPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PipelineMetricPointMapperTest {
    @Test
    void mapsPipelineProgressSnapshotToGenericMetricPoint() {
        ExecutionMetricsSnapshot snapshot = new ExecutionMetricsSnapshot(
                3, 2, 30.0d, 20.0d, 1.5d, 2.5d, 0.4d, 0.5d,
                4.0d, 12.0d, 1.2d, 1, 64.0d, 256.0d, 80.0d, 5.0d,
                2, 1, 3.0d, 7.0d, 100, 95, 90, 6, 10, 10.0d,
                2, 1L, 300L, 280L, 260L, 5L, 0.3d, 0.5d, 0.7d, 2.0d, 1200L);

        MetricPoint point = PipelineMetricPointMapper.fromProgressSnapshot(snapshot);

        assertEquals(3.0d, point.gauges().get("queue.raw.size"));
        assertEquals(300L, point.counters().get("throughput.producedPerSec"));
        assertEquals(90L, point.counters().get("rows.inserted"));
        assertEquals("pipeline.progressSnapshot", point.labels().get("source"));
    }
}
