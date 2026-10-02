package com.pnomeer.lab.storage;

import com.pnomeer.lab.core.MetricPoint;
import com.pnomeer.lab.core.storage.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Controlled storage-latency comparison, not a claim about end-to-end ingestion throughput. */
class MetricWriterPerformanceTest {
    @Test
    void boundedBatchingOutperformsPerPointWritesUnderFixedStorageLatency() throws Exception {
        RunStore store = mock(RunStore.class);
        AtomicInteger saved = new AtomicInteger(), maxBatch = new AtomicInteger();
        doAnswer(invocation -> {
            List<StoredMetric> batch = invocation.getArgument(1);
            Thread.sleep(3); // deterministic per-transaction latency for both strategies
            saved.addAndGet(batch.size()); maxBatch.accumulateAndGet(batch.size(), Math::max);
            return null;
        }).when(store).appendMetrics(anyString(), anyList());
        var point = new MetricPoint(1, Map.of(), Map.of(), Map.of());
        int count = 200;
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) store.appendMetrics("baseline", List.of(new StoredMetric(i, point)));
        long baseline = System.nanoTime() - start;
        saved.set(0);
        start = System.nanoTime();
        var writer = new BoundedMetricWriter(store, "batched", new MetricWriterOptions(32,20,1000,1,0,5000));
        try (writer) { for (int i = 0; i < count; i++) writer.accept(point); }
        long batched = System.nanoTime() - start;
        assertEquals(count, saved.get());
        assertTrue(maxBatch.get() <= 20);
        assertTrue(writer.counters().get("storage.queue.maxDepth") <= 32);
        assertTrue(batched < baseline, "Batching should reduce fixed per-write latency");
        Path report = Path.of("build/reports/metric-writer-benchmark.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
                {"points":%d,"simulatedWriteLatencyMs":3,"queueCapacity":32,"batchSize":20,
                 "baselineNanos":%d,"batchedNanos":%d,"maxQueueDepth":%d,
                 "javaVersion":"%s","availableProcessors":%d}
                """.formatted(count, baseline, batched, writer.counters().get("storage.queue.maxDepth"),
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors()));
    }
}
