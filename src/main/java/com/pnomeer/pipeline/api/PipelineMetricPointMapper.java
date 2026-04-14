package com.pnomeer.pipeline.api;

import com.pnomeer.lab.core.MetricPoint;
import com.pnomeer.lab.metrics.ExecutionMetricsSnapshot;

import java.util.Map;

public final class PipelineMetricPointMapper {
    private PipelineMetricPointMapper() {
    }

    public static MetricPoint fromProgressSnapshot(ExecutionMetricsSnapshot snapshot) {
        return new MetricPoint(
                System.currentTimeMillis(),
                Map.ofEntries(
                        Map.entry("queue.raw.size", (double) snapshot.rawQueueSize()),
                        Map.entry("queue.mapped.size", (double) snapshot.mappedQueueSize()),
                        Map.entry("queue.raw.saturationPct", snapshot.rawQueueSaturationPct()),
                        Map.entry("queue.mapped.saturationPct", snapshot.mappedQueueSaturationPct()),
                        Map.entry("queue.raw.enqueueWaitMs", snapshot.rawEnqueueWaitMs()),
                        Map.entry("queue.mapped.enqueueWaitMs", snapshot.mappedEnqueueWaitMs()),
                        Map.entry("queue.raw.dequeueLatencyMs", snapshot.rawDequeueLatencyMs()),
                        Map.entry("queue.mapped.dequeueLatencyMs", snapshot.mappedDequeueLatencyMs()),
                        Map.entry("backpressure.enqueueBlockingTimeMs", snapshot.enqueueBlockingTimeMs()),
                        Map.entry("backpressure.producerSlowdownPct", snapshot.producerSlowdownPct()),
                        Map.entry("backpressure.stallFrequencyPerMin", snapshot.pipelineStallFrequencyPerMin()),
                        Map.entry("resource.heapUsedMb", snapshot.heapUsedMb()),
                        Map.entry("resource.heapMaxMb", snapshot.heapMaxMb()),
                        Map.entry("resource.peakHeapMb", snapshot.peakHeapMb()),
                        Map.entry("resource.gcPauseMs", snapshot.gcPauseMs()),
                        Map.entry("resource.connectionWaitMs", snapshot.connectionWaitMs()),
                        Map.entry("resource.lockWaitMs", snapshot.lockWaitMs()),
                        Map.entry("latency.parseMs", snapshot.parseLatencyMs()),
                        Map.entry("latency.validationMs", snapshot.validationLatencyMs()),
                        Map.entry("latency.mappingMs", snapshot.mappingLatencyMs()),
                        Map.entry("latency.insertMs", snapshot.insertLatencyMs()),
                        Map.entry("errors.validationRatePct", snapshot.validationErrorRatePct())),
                Map.ofEntries(
                        Map.entry("throughput.producedPerSec", snapshot.producedRatePerSec()),
                        Map.entry("throughput.mappedPerSec", snapshot.mappedRatePerSec()),
                        Map.entry("throughput.insertedPerSec", snapshot.insertedRatePerSec()),
                        Map.entry("throughput.batchPerSec", snapshot.batchRatePerSec()),
                        Map.entry("rows.produced", (long) snapshot.producedCount()),
                        Map.entry("rows.mapped", (long) snapshot.mappedCount()),
                        Map.entry("rows.inserted", (long) snapshot.insertedCount()),
                        Map.entry("rows.failed", (long) snapshot.failedRowCount()),
                        Map.entry("batches.completed", (long) snapshot.batchCount()),
                        Map.entry("retry.count", (long) snapshot.retryCount()),
                        Map.entry("retry.ratePerSec", snapshot.retryRatePerSec()),
                        Map.entry("resource.activeConnections", (long) snapshot.activeConnections()),
                        Map.entry("resource.awaitingConnections", (long) snapshot.awaitingConnections()),
                        Map.entry("backpressure.stallEvents", (long) snapshot.pipelineStallEventCount()),
                        Map.entry("time.elapsedMs", snapshot.elapsedMillis())),
                Map.of("source", "pipeline.progressSnapshot"));
    }
}
