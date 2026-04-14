package com.pnomeer.lab.experiments.concurrency;

import com.pnomeer.lab.core.ExperimentScenario;

import java.util.Map;

public record QueueContentionScenario(
        String scenarioId,
        int queueCapacity,
        int producerThreads,
        int consumerThreads,
        int itemsPerProducer,
        long consumerDelayMs) implements ExperimentScenario {

    public QueueContentionScenario {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be > 0");
        }
        if (producerThreads <= 0) {
            throw new IllegalArgumentException("producerThreads must be > 0");
        }
        if (consumerThreads <= 0) {
            throw new IllegalArgumentException("consumerThreads must be > 0");
        }
        if (itemsPerProducer <= 0) {
            throw new IllegalArgumentException("itemsPerProducer must be > 0");
        }
        if (consumerDelayMs < 0L) {
            throw new IllegalArgumentException("consumerDelayMs must be >= 0");
        }
    }

    @Override
    public String displayName() {
        return "queue-contention-" + scenarioId;
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of(
                "queueCapacity", String.valueOf(queueCapacity),
                "producerThreads", String.valueOf(producerThreads),
                "consumerThreads", String.valueOf(consumerThreads),
                "itemsPerProducer", String.valueOf(itemsPerProducer),
                "consumerDelayMs", String.valueOf(consumerDelayMs));
    }
}
