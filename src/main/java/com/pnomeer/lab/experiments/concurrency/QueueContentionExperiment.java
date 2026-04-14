package com.pnomeer.lab.experiments.concurrency;

import com.pnomeer.lab.core.Experiment;
import com.pnomeer.lab.core.ExperimentSummary;
import com.pnomeer.lab.core.MetricPoint;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

@Component
public class QueueContentionExperiment implements Experiment<QueueContentionScenario> {
    private static final int POISON_PILL = Integer.MIN_VALUE;

    @Override
    public String type() {
        return "concurrency.queue-contention";
    }

    @Override
    public ExperimentSummary execute(QueueContentionScenario scenario, Consumer<MetricPoint> timelineListener)
            throws Exception {
        long startedAtNanos = System.nanoTime();
        ArrayBlockingQueue<Integer> queue = new ArrayBlockingQueue<>(scenario.queueCapacity());
        ExecutorService producerPool = Executors.newFixedThreadPool(scenario.producerThreads());
        ExecutorService consumerPool = Executors.newFixedThreadPool(scenario.consumerThreads());
        AtomicInteger producedCount = new AtomicInteger();
        AtomicInteger consumedCount = new AtomicInteger();
        AtomicInteger maxQueueDepth = new AtomicInteger();
        LongAdder enqueueWaitNanos = new LongAdder();
        LongAdder blockingEventCount = new LongAdder();

        emitPoint(timelineListener, queue, producedCount.get(), consumedCount.get(), maxQueueDepth.get(), 0.0d, blockingEventCount.sum());

        try {
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
                producer.get();
            }
            for (int i = 0; i < scenario.consumerThreads(); i++) {
                queue.put(POISON_PILL);
            }
            for (Future<?> consumer : consumers) {
                consumer.get();
            }
        } finally {
            producerPool.shutdownNow();
            consumerPool.shutdownNow();
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
                blockingEventCount.sum());

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
                        "queueCapacity", String.valueOf(scenario.queueCapacity())));
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

    private static void emitPoint(
            Consumer<MetricPoint> timelineListener,
            ArrayBlockingQueue<Integer> queue,
            int producedCount,
            int consumedCount,
            int maxQueueDepth,
            double avgEnqueueWaitMs,
            long blockingEventCount) {
        timelineListener.accept(new MetricPoint(
                System.currentTimeMillis(),
                Map.of(
                        "queue.depth", (double) queue.size(),
                        "queue.maxDepth", (double) maxQueueDepth,
                        "queue.avgEnqueueWaitMs", avgEnqueueWaitMs),
                Map.of(
                        "items.produced", (long) producedCount,
                        "items.consumed", (long) consumedCount,
                        "blocking.events", blockingEventCount),
                Map.of("experiment", "queue-contention")));
    }
}
