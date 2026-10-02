package com.pnomeer.lab.metrics;

import java.util.*;

/** Versioned descriptions of emitted values; never infer units from a name suffix. */
public final class MetricCatalog {
    public static final String VERSION = "1";
    public record Definition(String name, String unit, String scope) { }
    private static final Map<String, Definition> DEFINITIONS = build();
    private MetricCatalog() { }
    public static Collection<Definition> definitions() { return DEFINITIONS.values(); }
    public static String unit(String name) { var d = DEFINITIONS.get(name); return d == null ? "unknown" : d.unit(); }
    private static Map<String, Definition> build() {
        var map = new TreeMap<String, Definition>();
        add(map, "ms", "run", "time.elapsedMs timing.elapsedMs queue.avgEnqueueWaitMs virtual.avgLatencyMs reactive.avgLatencyMs queue.raw.enqueueWaitMs queue.mapped.enqueueWaitMs queue.raw.dequeueLatencyMs queue.mapped.dequeueLatencyMs backpressure.enqueueBlockingTimeMs latency.parseMs latency.validationMs latency.mappingMs latency.insertMs resource.connectionWaitMs resource.lockWaitMs");
        add(map, "items", "run", "items.produced items.consumed queue.depth queue.maxDepth queue.raw.size queue.mapped.size");
        add(map, "rows", "run", "rows.produced rows.mapped rows.inserted rows.failed rows.validationErrors");
        add(map, "requests", "run", "requests.virtualCompleted requests.reactiveCompleted requests.totalCompleted");
        add(map, "requests/s", "run", "throughput.virtualRequestsPerSec throughput.reactiveRequestsPerSec throughput.totalRequestsPerSec");
        add(map, "items/s", "run", "throughput.itemsPerSec");
        add(map, "rows/s", "run", "throughput.producedPerSec throughput.mappedPerSec throughput.insertedPerSec");
        add(map, "%", "run", "queue.raw.saturationPct queue.mapped.saturationPct backpressure.producerSlowdownPct errors.validationRatePct");
        add(map, "events", "run", "blocking.events backpressure.stallEvents retry.count storage.retries storage.failures");
        add(map, "events/min", "run", "backpressure.stallFrequencyPerMin");
        add(map, "events/s", "run", "retry.ratePerSec");
        add(map, "batches", "run", "batches.completed storage.batches");
        add(map, "batches/s", "run", "throughput.batchPerSec");
        add(map, "connections", "run", "resource.activeConnections resource.awaitingConnections");
        add(map, "MiB", "process", "resource.heapUsedMb resource.heapMaxMb resource.peakHeapMb");
        add(map, "ms", "process", "resource.gcPauseMs jvm.gc.collectionTimeMs");
        add(map, "observations", "run", "storage.metrics.written storage.queue.depth storage.queue.maxDepth storage.preview.limit observation.jvm.samples observation.jvm.periodicSamples");
        add(map, "boolean", "run", "observation.jvm.capReached");
        add(map, "ns", "run", "storage.enqueueWaitNanos storage.writeNanos observation.jvm.captureNanos");
        add(map, "bytes", "process", "jvm.heap.usedBytes jvm.heap.committedBytes jvm.heap.maxBytes");
        add(map, "threads", "process", "jvm.threads.platformLive");
        add(map, "collections", "process", "jvm.gc.collectionCount");
        return Collections.unmodifiableMap(map);
    }
    private static void add(Map<String, Definition> map, String unit, String scope, String names) {
        for (String name : names.split(" ")) {
            if (map.putIfAbsent(name, new Definition(name, unit, scope)) != null) throw new IllegalStateException("Duplicate metric: " + name);
        }
    }
}
