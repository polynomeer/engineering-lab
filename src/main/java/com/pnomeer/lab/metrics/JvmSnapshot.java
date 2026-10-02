package com.pnomeer.lab.metrics;

import com.pnomeer.lab.core.MetricPoint;
import java.lang.management.*;
import java.util.*;

/** Process-wide MXBean values, not per-experiment resource attribution. */
public final class JvmSnapshot {
    private JvmSnapshot() { }
    public static MetricPoint capture() {
        return capture(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage(),
                ManagementFactory.getThreadMXBean().getThreadCount(), ManagementFactory.getGarbageCollectorMXBeans());
    }
    static MetricPoint capture(MemoryUsage heap, int threadCount, List<GarbageCollectorMXBean> collectors) {
        var gauges = new HashMap<String, Double>();
        if (heap.getUsed() >= 0) gauges.put("jvm.heap.usedBytes", (double) heap.getUsed());
        if (heap.getCommitted() >= 0) gauges.put("jvm.heap.committedBytes", (double) heap.getCommitted());
        if (heap.getMax() >= 0) gauges.put("jvm.heap.maxBytes", (double) heap.getMax());
        gauges.put("jvm.threads.platformLive", (double) threadCount);
        var counters = new HashMap<String, Long>();
        long count = 0, time = 0; boolean countKnown = !collectors.isEmpty(), timeKnown = countKnown;
        for (var collector : collectors) {
            long c = collector.getCollectionCount(), t = collector.getCollectionTime();
            countKnown &= c >= 0; timeKnown &= t >= 0;
            if (c >= 0) count += c; if (t >= 0) time += t;
        }
        if (countKnown) counters.put("jvm.gc.collectionCount", count);
        if (timeKnown) counters.put("jvm.gc.collectionTimeMs", time);
        return new MetricPoint(System.currentTimeMillis(), gauges, counters, Map.of());
    }
}
