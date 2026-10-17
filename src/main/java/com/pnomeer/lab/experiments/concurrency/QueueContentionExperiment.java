package com.pnomeer.lab.experiments.concurrency;

import com.pnomeer.lab.core.Experiment;
import com.pnomeer.lab.core.ExperimentSummary;
import com.pnomeer.lab.core.MetricPoint;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

@Component
public class QueueContentionExperiment implements Experiment<QueueContentionScenario> {
    private static final int POISON_PILL = Integer.MIN_VALUE;
    private final long metricIntervalMs;
    private final long shutdownTimeoutMs;

    public QueueContentionExperiment() { this(250L, 5000L); }

    @Autowired
    public QueueContentionExperiment(
            @Value("${lab.queue.metric-interval-ms:250}") long metricIntervalMs,
            @Value("${lab.queue.worker-shutdown-timeout-ms:5000}") long shutdownTimeoutMs) {
        if (metricIntervalMs < 10L || metricIntervalMs > 60000L)
            throw new IllegalArgumentException("lab.queue.metric-interval-ms must be 10..60000");
        if (shutdownTimeoutMs < 10L || shutdownTimeoutMs > 60000L)
            throw new IllegalArgumentException("lab.queue.worker-shutdown-timeout-ms must be 10..60000");
        this.metricIntervalMs = metricIntervalMs;
        this.shutdownTimeoutMs = shutdownTimeoutMs;
    }

    @Override
    public String type() {
        return "concurrency.queue-contention";
    }

    @Override
    public ExperimentSummary execute(QueueContentionScenario scenario, Consumer<MetricPoint> timelineListener)
            throws Exception {
        long startedAtNanos = System.nanoTime();
        ArrayBlockingQueue<Integer> queue = new ArrayBlockingQueue<>(scenario.queueCapacity());
        ExecutorService producerPool = Executors.newFixedThreadPool(scenario.producerThreads(), Thread.ofPlatform().name("lab-queue-producer-", 0).factory());
        ExecutorService consumerPool = Executors.newFixedThreadPool(scenario.consumerThreads(), Thread.ofPlatform().name("lab-queue-consumer-", 0).factory());
        AtomicInteger producedCount = new AtomicInteger();
        AtomicInteger consumedCount = new AtomicInteger();
        AtomicInteger maxQueueDepth = new AtomicInteger();
        LongAdder enqueueWaitNanos = new LongAdder();
        LongAdder blockingEventCount = new LongAdder();

        // The coordinator serializes callbacks; no scheduler, queue or sample list is added.
        Runnable observe = () -> {
            int produced = producedCount.get();
            emitPoint(timelineListener, queue, produced, consumedCount.get(), maxQueueDepth.get(),
                    produced == 0 ? 0.0d : (enqueueWaitNanos.sum() / 1_000_000.0d) / produced,
                    blockingEventCount.sum(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
        };
        ObservationTicker ticker = new ObservationTicker(metricIntervalMs, observe);
        try {
            observe.run();
            Future<?>[] producers = new Future<?>[scenario.producerThreads()];
            for (int producerIndex = 0; producerIndex < scenario.producerThreads(); producerIndex++) {
                producers[producerIndex] = producerPool.submit(() -> produce(
                        scenario,
                        queue,
                        producedCount,
                        maxQueueDepth,
                        enqueueWaitNanos,
                        blockingEventCount));
            }

            Future<?>[] consumers = new Future<?>[scenario.consumerThreads()];
            for (int consumerIndex = 0; consumerIndex < scenario.consumerThreads(); consumerIndex++) {
                consumers[consumerIndex] = consumerPool.submit(() -> consume(
                        scenario.consumerDelayMs(),
                        queue,
                        consumedCount,
                        maxQueueDepth));
            }

            for (Future<?> producer : producers) {
                await(producer, ticker);
            }
            for (int i = 0; i < scenario.consumerThreads(); i++) {
                do {
                    ticker.sampleIfDue();
                } while (!queue.offer(POISON_PILL, ticker.untilNext(), TimeUnit.NANOSECONDS));
            }
            for (Future<?> consumer : consumers) {
                await(consumer, ticker);
            }
        } finally {
            producerPool.shutdownNow();
            consumerPool.shutdownNow();
            if (!producerPool.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)
                    || !consumerPool.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Queue workers did not terminate within the configured timeout");
            }
        }

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        double avgEnqueueWaitMs = producedCount.get() == 0
                ? 0.0d
                : (enqueueWaitNanos.sum() / 1_000_000.0d) / producedCount.get();
        double throughputPerSec = elapsedMillis <= 0L
                ? producedCount.get()
                : (producedCount.get() * 1000.0d) / elapsedMillis;

        emitPoint(
                timelineListener,
                queue,
                producedCount.get(),
                consumedCount.get(),
                maxQueueDepth.get(),
                avgEnqueueWaitMs,
                blockingEventCount.sum(), elapsedMillis);

        return new ExperimentSummary(
                Map.of(
                        "items.produced", (long) producedCount.get(),
                        "items.consumed", (long) consumedCount.get(),
                        "blocking.events", blockingEventCount.sum()),
                Map.of(
                        "queue.maxDepth", (double) maxQueueDepth.get(),
                        "queue.avgEnqueueWaitMs", avgEnqueueWaitMs,
                        "throughput.itemsPerSec", throughputPerSec,
                        "time.elapsedMs", (double) elapsedMillis),
                Map.of(
                        "experiment", type(),
                        "queueCapacity", String.valueOf(scenario.queueCapacity()),
                        "metricIntervalMs", String.valueOf(metricIntervalMs)));
    }

    private static void await(Future<?> future, ObservationTicker ticker) throws Exception {
        while (true) {
            ticker.sampleIfDue();
            try {
                future.get(ticker.untilNext(), TimeUnit.NANOSECONDS);
                return;
            } catch (TimeoutException waiting) {
                // Sample on the next iteration while worker futures remain outstanding.
            }
        }
    }

    private static final class ObservationTicker {
        private final long intervalNanos;
        private final Runnable observer;
        private long next;
        ObservationTicker(long intervalMs, Runnable observer) {
            this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMs);
            this.observer = observer;
            this.next = System.nanoTime() + intervalNanos;
        }
        void sampleIfDue() {
            if (System.nanoTime() - next >= 0) {
                observer.run();
                next = System.nanoTime() + intervalNanos;
            }
        }
        long untilNext() { return Math.max(1L, next - System.nanoTime()); }
    }

    private static void produce(
            QueueContentionScenario scenario,
            ArrayBlockingQueue<Integer> queue,
            AtomicInteger producedCount,
            AtomicInteger maxQueueDepth,
            LongAdder enqueueWaitNanos,
            LongAdder blockingEventCount) {
        for (int i = 0; i < scenario.itemsPerProducer(); i++) {
            long start = System.nanoTime();
            try {
                queue.put(i);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            long waitNanos = Math.max(0L, System.nanoTime() - start);
            if (waitNanos > TimeUnit.MILLISECONDS.toNanos(1)) {
                blockingEventCount.increment();
            }
            enqueueWaitNanos.add(waitNanos);
            producedCount.incrementAndGet();
            maxQueueDepth.accumulateAndGet(queue.size(), Math::max);
        }
    }

    private static void consume(
            long consumerDelayMs,
            ArrayBlockingQueue<Integer> queue,
            AtomicInteger consumedCount,
            AtomicInteger maxQueueDepth) {
        while (true) {
            Integer value;
            try {
                value = queue.take();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if (value == POISON_PILL) {
                return;
            }
            if (consumerDelayMs > 0L) {
                try {
                    Thread.sleep(consumerDelayMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            consumedCount.incrementAndGet();
            maxQueueDepth.accumulateAndGet(queue.size(), Math::max);
        }
    }

    private void emitPoint(
            Consumer<MetricPoint> timelineListener,
            ArrayBlockingQueue<Integer> queue,
            int producedCount,
            int consumedCount,
            int maxQueueDepth,
            double avgEnqueueWaitMs,
            long blockingEventCount,
            long elapsedMillis) {
        timelineListener.accept(new MetricPoint(
                System.currentTimeMillis(),
                Map.of(
                        "queue.depth", (double) queue.size(),
                        "queue.maxDepth", (double) maxQueueDepth,
                        "queue.avgEnqueueWaitMs", avgEnqueueWaitMs),
                Map.of(
                        "items.produced", (long) producedCount,
                        "items.consumed", (long) consumedCount,
                        "blocking.events", blockingEventCount,
                        "time.elapsedMs", elapsedMillis),
                Map.of("experiment", "queue-contention", "sampleClock", "monotonic",
                        "metric.interval.ms", String.valueOf(metricIntervalMs))));
    }
}
