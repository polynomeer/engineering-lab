package com.pnomeer.lab.experiments.pipeline.queue;

import com.pnomeer.lab.experiments.pipeline.config.PipelineProperties;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

public class BoundedChannel<T> {
    private final java.util.concurrent.atomic.AtomicReference<RuntimeException> failure = new java.util.concurrent.atomic.AtomicReference<>();

    public void fail(RuntimeException cause) { failure.compareAndSet(null, cause); }
    public void checkFailure() {
        RuntimeException cause = failure.get();
        if (cause != null) throw new IllegalStateException("Channel aborted after worker failure", cause);
    }

    private T awaitValue(long timeoutNanos) throws InterruptedException {
        long start = System.nanoTime();
        long remaining = timeoutNanos;
        do {
            checkFailure();
            T value = queue.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
            checkFailure();
            if (value != null) return value;
            remaining = timeoutNanos - (System.nanoTime() - start);
        } while (remaining > 0);
        return null;
    }

    private final int capacity;
    private final ArrayBlockingQueue<T> queue;
    private final Long offerTimeoutMs;
    private final LongAdder enqueuedCount = new LongAdder();
    private final LongAdder dequeuedCount = new LongAdder();
    private final LongAdder cumulativeEnqueueWaitNanos = new LongAdder();
    private final LongAdder cumulativeDequeueWaitNanos = new LongAdder();

    public BoundedChannel(int capacity, PipelineProperties properties) {
        this(capacity, properties.getBackpressure().getOfferTimeoutMs());
    }

    public BoundedChannel(int capacity, Long offerTimeoutMs) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.offerTimeoutMs = offerTimeoutMs;
    }

    public void put(T value) throws InterruptedException {
        long start = System.nanoTime();
        try {
            do { checkFailure(); } while (!queue.offer(value, 100, TimeUnit.MILLISECONDS));
            checkFailure();
            enqueuedCount.increment();
        } finally {
            cumulativeEnqueueWaitNanos.add(System.nanoTime() - start);
        }
    }

    public boolean offer(T value) throws InterruptedException {
        if (offerTimeoutMs == null) {
            put(value);
            return true;
        }
        return offer(value, offerTimeoutMs);
    }

    public boolean offer(T value, long timeoutMs) throws InterruptedException {
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("timeoutMs must be >= 0");
        }
        long start = System.nanoTime();
        try {
            long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            long remaining = timeoutNanos;
            boolean accepted;
            do {
                checkFailure();
                accepted = queue.offer(value, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                checkFailure();
                if (accepted) break;
                remaining = timeoutNanos - (System.nanoTime() - start);
            } while (remaining > 0);
            if (accepted) {
                enqueuedCount.increment();
            }
            return accepted;
        } finally {
            cumulativeEnqueueWaitNanos.add(System.nanoTime() - start);
        }
    }

    public T take() throws InterruptedException {
        long start = System.nanoTime();
        try {
            T value = awaitValue(Long.MAX_VALUE);
            dequeuedCount.increment();
            return value;
        } finally {
            cumulativeDequeueWaitNanos.add(System.nanoTime() - start);
        }
    }

    /** A timeout is not a dequeue; waiting time still contributes to channel metrics. */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (timeout < 0) throw new IllegalArgumentException("timeout must be >= 0");
        long start = System.nanoTime();
        try {
            T value = awaitValue(unit.toNanos(timeout));
            if (value != null) dequeuedCount.increment();
            return value;
        } finally {
            cumulativeDequeueWaitNanos.add(System.nanoTime() - start);
        }
    }

    public int size() {
        return queue.size();
    }

    public int capacity() {
        return capacity;
    }

    public long getEnqueuedCount() {
        return enqueuedCount.sum();
    }

    public long getDequeuedCount() {
        return dequeuedCount.sum();
    }

    public long getCumulativeEnqueueWaitNanos() {
        return cumulativeEnqueueWaitNanos.sum();
    }

    public long getCumulativeDequeueWaitNanos() {
        return cumulativeDequeueWaitNanos.sum();
    }
}
