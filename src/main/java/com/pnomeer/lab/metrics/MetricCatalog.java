package com.pnomeer.lab.metrics;

import java.util.*;

/** Versioned descriptions of emitted values; never infer units from a name suffix. */
public final class MetricCatalog {
    public static final String VERSION = "2";
    public record Definition(String name, String unit, String scope, String semantics, String aggregation) { }
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
        add(map, "events", "run", "blocking.events backpressure.stallEvents retry.count storage.retries storage.failures observation.logs.events observation.logs.dropped");
        add(map, "events/min", "run", "backpressure.stallFrequencyPerMin");
        add(map, "events/s", "run", "retry.ratePerSec");
        add(map, "batches", "run", "batches.completed storage.batches");
        add(map, "batches/s", "run", "throughput.batchPerSec");
        add(map, "connections", "run", "resource.activeConnections resource.awaitingConnections");
        add(map, "MiB", "process", "resource.heapUsedMb resource.heapMaxMb resource.peakHeapMb");
        add(map, "ms", "process", "resource.gcPauseMs jvm.gc.collectionTimeMs");
        add(map, "observations", "run", "storage.metrics.written storage.queue.depth storage.queue.maxDepth storage.preview.limit observation.jvm.samples observation.jvm.periodicSamples");
        add(map, "boolean", "run", "observation.jvm.capReached observation.jfr.durationLimitReached");
        add(map, "spans", "run", "trace.spans.recorded trace.spans.dropped");
        add(map, "ns", "span", "trace.durationNanos");
        add(map, "ns", "run", "storage.enqueueWaitNanos storage.writeNanos observation.jvm.captureNanos observation.jfr.lifecycleNanos observation.logs.captureNanos");
        add(map, "bytes", "run", "observation.jfr.bytes observation.logs.bytes");
        add(map, "bytes", "process", "jvm.heap.usedBytes jvm.heap.committedBytes jvm.heap.maxBytes");
        add(map, "threads", "process", "jvm.threads.platformLive");
        add(map, "collections", "process", "jvm.gc.collectionCount");
        return Collections.unmodifiableMap(map);
    }
    private static Definition definition(String name, String unit, String scope) {
        String semantics = "UNKNOWN", aggregation = "Do not aggregate without checking the producer";
        if (Set.of("observation.logs.events", "observation.logs.dropped", "observation.logs.bytes", "observation.logs.captureNanos", "items.produced", "items.consumed", "rows.produced", "rows.mapped", "rows.inserted", "rows.failed", "rows.validationErrors",
                "requests.virtualCompleted", "requests.reactiveCompleted", "requests.totalCompleted", "blocking.events", "backpressure.stallEvents",
                "observation.jfr.bytes", "observation.jfr.lifecycleNanos", "trace.spans.recorded", "trace.spans.dropped", "retry.count", "batches.completed", "storage.batches", "storage.retries", "storage.failures", "storage.metrics.written",
                "storage.enqueueWaitNanos", "storage.writeNanos", "observation.jvm.samples", "observation.jvm.periodicSamples",
                "observation.jvm.captureNanos", "time.elapsedMs", "timing.elapsedMs").contains(name)) {
            semantics = "RUN_CUMULATIVE"; aggregation = "Use the final value; never sum repeated snapshots";
        } else if (Set.of("jvm.gc.collectionCount", "jvm.gc.collectionTimeMs").contains(name)) {
            semantics = "PROCESS_CUMULATIVE"; aggregation = "Delta within the same process; not attributable to one Run";
        } else if (Set.of("queue.avgEnqueueWaitMs", "queue.raw.enqueueWaitMs", "queue.mapped.enqueueWaitMs", "queue.raw.dequeueLatencyMs",
                "queue.mapped.dequeueLatencyMs", "virtual.avgLatencyMs", "reactive.avgLatencyMs", "latency.parseMs", "latency.validationMs",
                "latency.mappingMs", "latency.insertMs", "resource.connectionWaitMs", "resource.lockWaitMs").contains(name)) {
            semantics = "RUN_MEAN"; aggregation = "Do not average snapshot means; require matching counts and totals";
        } else if (Set.of("throughput.producedPerSec", "throughput.mappedPerSec", "throughput.insertedPerSec", "throughput.batchPerSec", "retry.ratePerSec").contains(name)) {
            semantics = "INTERVAL_RATE"; aggregation = "Weight by actual interval; rounded rates cannot recover exact counts";
        } else if (Set.of("throughput.itemsPerSec", "throughput.virtualRequestsPerSec", "throughput.reactiveRequestsPerSec", "throughput.totalRequestsPerSec", "backpressure.stallFrequencyPerMin").contains(name)) {
            semantics = "RUN_RATE"; aggregation = "Use the final rate; inspect producer denominator before comparing";
        } else if (Set.of("backpressure.enqueueBlockingTimeMs", "resource.gcPauseMs").contains(name)) {
            semantics = "INTERVAL_DELTA"; aggregation = "Sum only disjoint complete intervals; GC collection time is not pause latency";
        } else if (Set.of("queue.maxDepth", "resource.peakHeapMb", "storage.queue.maxDepth").contains(name)) {
            semantics = "OBSERVED_MAXIMUM"; aggregation = "Maximum of observed snapshots; unobserved peaks may be missed";
        } else if (Set.of("queue.depth", "queue.raw.size", "queue.mapped.size", "resource.heapUsedMb", "resource.heapMaxMb",
                "resource.activeConnections", "resource.awaitingConnections", "storage.queue.depth", "jvm.heap.usedBytes",
                "jvm.heap.committedBytes", "jvm.heap.maxBytes", "jvm.threads.platformLive").contains(name)) {
            semantics = "INSTANTANEOUS"; aggregation = "Use timestamped samples; never sum gauges across time";
        } else if (Set.of("queue.raw.saturationPct", "queue.mapped.saturationPct", "backpressure.producerSlowdownPct", "errors.validationRatePct").contains(name)) {
            semantics = "RATIO"; aggregation = "Inspect numerator and denominator; never sum percentages";
        } else if (name.equals("trace.durationNanos")) {
            semantics = "SPAN_DURATION"; aggregation = "Inclusive monotonic duration; never sum overlapping parent and child spans";
        } else if (name.equals("storage.preview.limit")) {
            semantics = "CONFIGURATION"; aggregation = "Recorded limit, not an observation count";
        } else if (name.equals("observation.jfr.durationLimitReached")) {
            semantics = "FLAG"; aggregation = "0=false, 1=true; recording may end before the experiment";
        } else if (name.equals("observation.jvm.capReached")) {
            semantics = "FLAG"; aggregation = "0=false, 1=true; subsequent periodic observations may be absent";
        }
        return new Definition(name,unit,scope,semantics,aggregation);
    }
    private static void add(Map<String, Definition> map, String unit, String scope, String names) {
        for (String name : names.split(" ")) {
            if (map.putIfAbsent(name, definition(name, unit, scope)) != null) throw new IllegalStateException("Duplicate metric: " + name);
        }
    }
}
