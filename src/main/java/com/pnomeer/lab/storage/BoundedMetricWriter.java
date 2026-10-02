package com.pnomeer.lab.storage;

import com.pnomeer.lab.core.MetricPoint;
import com.pnomeer.lab.core.storage.RunStore;
import com.pnomeer.lab.core.storage.StoredMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** One bounded queue and batch per active run. Completion waits for durable writes. */
public final class BoundedMetricWriter implements Consumer<MetricPoint>, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BoundedMetricWriter.class);
    private final RunStore store;
    private final String runId;
    private final MetricWriterOptions options;
    private final ArrayBlockingQueue<StoredMetric> queue;
    private final ReentrantLock publishing = new ReentrantLock();
    private final Thread worker;
    private volatile boolean closing;
    private volatile RuntimeException failure;
    private long sequence;
    private final AtomicLong written = new AtomicLong(), batches = new AtomicLong(), retries = new AtomicLong(),
            failures = new AtomicLong(), waitNanos = new AtomicLong(), writeNanos = new AtomicLong(), maxDepth = new AtomicLong();

    public BoundedMetricWriter(RunStore store, String runId, MetricWriterOptions options) {
        this.store = store;
        this.runId = runId;
        this.options = options;
        queue = new ArrayBlockingQueue<>(options.queueCapacity());
        worker = Thread.ofVirtual().name("metric-writer-" + runId).start(this::consume);
    }

    @Override
    public void accept(MetricPoint point) {
        java.util.Objects.requireNonNull(point);
        long start = System.nanoTime();
        try {
            publishing.lockInterruptibly();
            try {
                checkFailure();
                if (closing) throw new IllegalStateException("Metric writer is closed");
                var metric = new StoredMetric(sequence, point);
                while (!queue.offer(metric, 50, TimeUnit.MILLISECONDS)) checkFailure();
                sequence++;
                maxDepth.accumulateAndGet(queue.size(), Math::max);
                checkFailure();
            } finally { publishing.unlock(); }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Metric publication interrupted", ex);
        } finally { waitNanos.addAndGet(System.nanoTime() - start); }
    }

    private void consume() {
        var batch = new ArrayList<StoredMetric>(options.batchSize());
        long deadline = 0;
        try {
            while (!closing || !queue.isEmpty()) {
                long waitMs = batch.isEmpty() ? 50 : Math.max(1, Math.min(50,
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                StoredMetric metric = queue.poll(waitMs, TimeUnit.MILLISECONDS);
                if (metric != null) {
                    if (batch.isEmpty()) deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.flushIntervalMs());
                    batch.add(metric);
                }
                if (!batch.isEmpty() && (batch.size() >= options.batchSize() || System.nanoTime() >= deadline)) {
                    flush(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) flush(batch);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            failure = new IllegalStateException("Observation storage failed for run " + runId, ex);
            failures.incrementAndGet();
            log.error("job_id={} stage=metric-storage reason=write_failed pending={} batch_size={}", runId, queue.size(), batch.size(), ex);
        }
    }

    private void flush(List<StoredMetric> batch) throws InterruptedException {
        long start = System.nanoTime();
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    store.appendMetrics(runId, batch);
                    written.addAndGet(batch.size());
                    batches.incrementAndGet();
                    return;
                } catch (RuntimeException ex) {
                    if (attempt >= options.maxAttempts()) throw ex;
                    retries.incrementAndGet();
                    log.warn("job_id={} stage=metric-storage reason=retry attempt={}", runId, attempt);
                    Thread.sleep(options.retryBackoffMs());
                }
            }
        } finally { writeNanos.addAndGet(System.nanoTime() - start); }
    }

    public Map<String, Long> counters() {
        return Map.of("storage.metrics.written", written.get(), "storage.batches", batches.get(),
                "storage.retries", retries.get(), "storage.failures", failures.get(),
                "storage.queue.depth", (long) queue.size(), "storage.queue.maxDepth", maxDepth.get(),
                "storage.enqueueWaitNanos", waitNanos.get(), "storage.writeNanos", writeNanos.get());
    }

    private void checkFailure() { if (failure != null) throw failure; }

    @Override
    public void close() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.shutdownTimeoutMs());
        try {
            if (!publishing.tryLock(options.shutdownTimeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out closing metric publisher");
            }
            try { closing = true; } finally { publishing.unlock(); }
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining > 0) worker.join(remaining);
            if (worker.isAlive()) throw new IllegalStateException("Timed out draining metric storage");
            checkFailure();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            failure = new IllegalStateException("Metric drain interrupted", ex);
            worker.interrupt();
            throw failure;
        } catch (RuntimeException ex) {
            failure = ex;
            worker.interrupt();
            throw ex;
        }
    }
}
