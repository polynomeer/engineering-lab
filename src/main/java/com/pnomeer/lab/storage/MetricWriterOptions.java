package com.pnomeer.lab.storage;

public record MetricWriterOptions(int queueCapacity, int batchSize, long flushIntervalMs,
        int maxAttempts, long retryBackoffMs, long shutdownTimeoutMs) {
    public MetricWriterOptions {
        if (queueCapacity < 1 || batchSize < 1 || flushIntervalMs < 1 || maxAttempts < 1
                || retryBackoffMs < 0 || shutdownTimeoutMs < 1) {
            throw new IllegalArgumentException("Invalid metric writer limits");
        }
    }
}
