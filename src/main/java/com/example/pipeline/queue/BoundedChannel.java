package com.example.pipeline.queue;

import com.polynomeer.excelpipeline.config.PipelineProperties;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

public class BoundedChannel<T> {
    private final ArrayBlockingQueue<T> queue;
    private final Long offerTimeoutMs;
    private final LongAdder enqueuedCount = new LongAdder();
    private final LongAdder dequeuedCount = new LongAdder();
    private final LongAdder cumulativeEnqueueWaitNanos = new LongAdder();

    public BoundedChannel(int capacity, PipelineProperties properties) {
        this(capacity, properties.getBackpressure().getOfferTimeoutMs());
    }

    public BoundedChannel(int capacity, Long offerTimeoutMs) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.offerTimeoutMs = offerTimeoutMs;
    }

    public void put(T value) throws InterruptedException {
        long start = System.nanoTime();
        try {
            queue.put(value);
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
            boolean accepted = queue.offer(value, timeoutMs, TimeUnit.MILLISECONDS);
            if (accepted) {
                enqueuedCount.increment();
            }
            return accepted;
        } finally {
            cumulativeEnqueueWaitNanos.add(System.nanoTime() - start);
        }
    }

    public T take() throws InterruptedException {
        T value = queue.take();
        dequeuedCount.increment();
        return value;
    }

    public int size() {
        return queue.size();
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
}
