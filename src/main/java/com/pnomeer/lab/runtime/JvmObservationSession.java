package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.MetricPoint;
import com.pnomeer.lab.metrics.MetricCatalog;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** One fixed-delay sampler per admitted run; no scheduled task queue or catch-up bursts. */
final class JvmObservationSession {
    private final Supplier<MetricPoint> probe;
    private final Consumer<MetricPoint> sink;
    private final long intervalMs, stopTimeoutMs;
    private final int maxSamples;
    private final Object wakeup = new Object();
    private final AtomicLong samples = new AtomicLong(), nanos = new AtomicLong(), periodic = new AtomicLong();
    private volatile boolean stopping, capped;
    private volatile Throwable failure;
    private Thread worker;
    private final String runId;

    JvmObservationSession(Supplier<MetricPoint> probe, Consumer<MetricPoint> sink, long intervalMs,
            int maxSamples, long stopTimeoutMs, String runId) {
        this.probe=probe; this.sink=sink; this.intervalMs=intervalMs; this.maxSamples=maxSamples;
        this.stopTimeoutMs=stopTimeoutMs; this.runId=runId;
    }
    void start() {
        if (probe == null) return;
        capture("start");
        if (intervalMs > 0) worker = Thread.ofVirtual().name("jvm-observer-"+runId).start(this::sample);
    }
    private void sample() {
        try {
            while (!stopping && periodic.get() < maxSamples) {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(intervalMs);
                synchronized (wakeup) {
                    while (!stopping) {
                        long left = deadline - System.nanoTime();
                        if (left <= 0) break;
                        java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(wakeup, left);
                    }
                }
                if (stopping) return;
                capture("periodic");
            }
            capped = periodic.get() >= maxSamples;
        } catch (Throwable ex) { failure = ex; }
    }
    private void capture(String phase) {
        long began = System.nanoTime();
        MetricPoint point;
        try { point = probe.get(); }
        finally { nanos.addAndGet(System.nanoTime()-began); }
        if (phase.equals("periodic") && stopping) return;
        sink.accept(new MetricPoint(point.timestampEpochMs(),point.gauges(),point.counters(),
                Map.of("source",phase.equals("periodic")?"jvm.periodic":"jvm.boundary", "scope","process",
                        "phase",phase,"schemaVersion",MetricCatalog.VERSION)));
        samples.incrementAndGet();
        if (phase.equals("periodic")) periodic.incrementAndGet();
    }
    void checkFailure() {
        if (failure != null) throw new IllegalStateException("Periodic JVM observation failed",failure);
    }
    void finish() {
        if (probe == null) return;
        stopping=true;
        synchronized (wakeup) { wakeup.notifyAll(); }
        if (worker != null) {
            try { worker.join(stopTimeoutMs); }
            catch (InterruptedException ex) {
                worker.interrupt(); Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted stopping JVM sampler",ex);
            }
            if (worker.isAlive()) {
                worker.interrupt();
                throw new IllegalStateException("Timed out stopping JVM sampler");
            }
        }
        checkFailure();
        capture("end");
    }
    Map<String,Long> counters() {
        return probe == null ? Map.of() : Map.of("observation.jvm.samples",samples.get(),
                "observation.jvm.captureNanos",nanos.get(), "observation.jvm.periodicSamples",periodic.get(),
                "observation.jvm.capReached",capped?1L:0L);
    }
}
