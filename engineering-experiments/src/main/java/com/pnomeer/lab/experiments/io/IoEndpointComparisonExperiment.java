package com.pnomeer.lab.experiments.io;

import com.pnomeer.lab.core.Experiment;
import com.pnomeer.lab.core.ExperimentSummary;
import com.pnomeer.lab.core.MetricPoint;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
public class IoEndpointComparisonExperiment implements Experiment<IoEndpointComparisonScenario> {
    private static final Object PIN_LOCK = new Object();

    private final ExecutorService ioVirtualThreadExecutor;

    public IoEndpointComparisonExperiment(@Qualifier("ioVirtualThreadExecutor") ExecutorService ioVirtualThreadExecutor) {
        this.ioVirtualThreadExecutor = ioVirtualThreadExecutor;
    }

    @Override
    public String type() {
        return "io.endpoint-comparison";
    }

    @Override
    public ExperimentSummary execute(IoEndpointComparisonScenario scenario, Consumer<MetricPoint> timelineListener)
            throws Exception {
        long startedAtNanos = System.nanoTime();
        int virtualCompleted = 0;
        int reactiveCompleted = 0;
        long virtualLatencyNanos = 0L;
        long reactiveLatencyNanos = 0L;

        emitPoint(timelineListener, startedAtNanos, virtualCompleted, reactiveCompleted, virtualLatencyNanos, reactiveLatencyNanos);

        for (int i = 0; i < scenario.requests(); i++) {
            virtualLatencyNanos += runVirtualRequest(scenario);
            virtualCompleted++;
            emitPoint(timelineListener, startedAtNanos, virtualCompleted, reactiveCompleted, virtualLatencyNanos, reactiveLatencyNanos);
        }

        for (int i = 0; i < scenario.requests(); i++) {
            reactiveLatencyNanos += runReactiveRequest(scenario.delayMs());
            reactiveCompleted++;
            emitPoint(timelineListener, startedAtNanos, virtualCompleted, reactiveCompleted, virtualLatencyNanos, reactiveLatencyNanos);
        }

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        double virtualAvgLatencyMs = averageMillis(virtualLatencyNanos, virtualCompleted);
        double reactiveAvgLatencyMs = averageMillis(reactiveLatencyNanos, reactiveCompleted);
        double virtualRps = throughputPerSecond(virtualCompleted, virtualLatencyNanos);
        double reactiveRps = throughputPerSecond(reactiveCompleted, reactiveLatencyNanos);
        double totalRps = throughputPerSecond(virtualCompleted + reactiveCompleted, virtualLatencyNanos + reactiveLatencyNanos);
        String winner = virtualAvgLatencyMs <= reactiveAvgLatencyMs ? "virtual-thread" : "reactive";

        return new ExperimentSummary(
                Map.of(
                        "requests.virtualCompleted", (long) virtualCompleted,
                        "requests.reactiveCompleted", (long) reactiveCompleted,
                        "requests.totalCompleted", (long) (virtualCompleted + reactiveCompleted)),
                Map.of(
                        "virtual.avgLatencyMs", virtualAvgLatencyMs,
                        "reactive.avgLatencyMs", reactiveAvgLatencyMs,
                        "throughput.virtualRequestsPerSec", virtualRps,
                        "throughput.reactiveRequestsPerSec", reactiveRps,
                        "throughput.totalRequestsPerSec", totalRps,
                        "time.elapsedMs", (double) elapsedMillis),
                Map.of(
                        "experiment", type(),
                        "winner", winner,
                        "pinning", String.valueOf(scenario.pinning())));
    }

    private long runVirtualRequest(IoEndpointComparisonScenario scenario) throws Exception {
        long startedAtNanos = System.nanoTime();
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                if (scenario.pinning()) {
                    synchronized (PIN_LOCK) {
                        Thread.sleep(scenario.pinDelayMs());
                    }
                }
                Thread.sleep(scenario.delayMs());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("virtual-thread comparison interrupted", ex);
            }
        }, ioVirtualThreadExecutor);
        future.get();
        return Math.max(0L, System.nanoTime() - startedAtNanos);
    }

    private long runReactiveRequest(long delayMs) {
        long startedAtNanos = System.nanoTime();
        Mono.delay(Duration.ofMillis(delayMs)).block();
        return Math.max(0L, System.nanoTime() - startedAtNanos);
    }

    private static void emitPoint(
            Consumer<MetricPoint> timelineListener,
            long startedAtNanos,
            int virtualCompleted,
            int reactiveCompleted,
            long virtualLatencyNanos,
            long reactiveLatencyNanos) {
        timelineListener.accept(new MetricPoint(
                System.currentTimeMillis(),
                Map.of(
                        "virtual.avgLatencyMs", averageMillis(virtualLatencyNanos, virtualCompleted),
                        "reactive.avgLatencyMs", averageMillis(reactiveLatencyNanos, reactiveCompleted),
                        "throughput.virtualRequestsPerSec", throughputPerSecond(virtualCompleted, virtualLatencyNanos),
                        "throughput.reactiveRequestsPerSec", throughputPerSecond(reactiveCompleted, reactiveLatencyNanos),
                        "throughput.totalRequestsPerSec", throughputPerSecond(
                                virtualCompleted + reactiveCompleted,
                                Math.max(1L, System.nanoTime() - startedAtNanos))),
                Map.of(
                        "requests.virtualCompleted", (long) virtualCompleted,
                        "requests.reactiveCompleted", (long) reactiveCompleted,
                        "requests.totalCompleted", (long) (virtualCompleted + reactiveCompleted)),
                Map.of("experiment", "io-endpoint-comparison")));
    }

    private static double averageMillis(long totalNanos, int count) {
        if (count <= 0) {
            return 0.0d;
        }
        return (totalNanos / 1_000_000.0d) / count;
    }

    private static double throughputPerSecond(long count, long totalNanos) {
        if (count <= 0 || totalNanos <= 0L) {
            return 0.0d;
        }
        return count * 1_000_000_000.0d / totalNanos;
    }
}
