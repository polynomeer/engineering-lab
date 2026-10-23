package com.pnomeer.lab.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Samples JVM heap used on a daemon thread, plus once at start and close. The maximum is a lower bound of
 * the true peak (GC and sampling interval hide spikes) and covers the whole JVM, not one task.
 */
public final class HeapSampler implements AutoCloseable {
    public static final Duration DEFAULT_INTERVAL = Duration.ofMillis(20);
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final AtomicLong peak = new AtomicLong();
    private final long gcStartMs;
    private final Thread thread;
    private volatile boolean running = true;
    private long gcMs = -1;

    private HeapSampler(Duration interval) {
        long sleep = Math.max(1, interval.toMillis());
        gcStartMs = gcTimeMs();
        sample();
        thread = Thread.ofPlatform().daemon().name("heap-sampler").unstarted(() -> {
            while (running) {
                sample();
                try { Thread.sleep(sleep); } catch (InterruptedException ex) { return; }
            }
        });
        thread.start();
    }

    public static HeapSampler start() { return start(DEFAULT_INTERVAL); }
    public static HeapSampler start(Duration interval) { return new HeapSampler(interval); }

    private void sample() { peak.accumulateAndGet(Math.max(0, memory.getHeapMemoryUsage().getUsed()), Math::max); }

    private long gcTimeMs() {
        return collectors.stream().mapToLong(gc -> Math.max(0, gc.getCollectionTime())).sum();
    }

    /** Highest sampled heap used so far, in bytes. */
    public long peakBytes() { return peak.get(); }

    /** Heap limit of this JVM in bytes, or -1 when undefined. */
    public long maxBytes() { return memory.getHeapMemoryUsage().getMax(); }

    /** Accumulated GC time between start and close; -1 before close. */
    public long gcMillis() { return gcMs; }

    @Override
    public void close() {
        if (!running) return;
        running = false;
        thread.interrupt();
        try { thread.join(1000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        sample();
        gcMs = Math.max(0, gcTimeMs() - gcStartMs);
    }
}
