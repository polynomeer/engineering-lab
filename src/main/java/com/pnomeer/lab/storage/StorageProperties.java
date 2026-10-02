package com.pnomeer.lab.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab.storage")
public record StorageProperties(String root, int queueCapacity, int batchSize, long flushIntervalMs,
        int maxAttempts, long retryBackoffMs, long shutdownTimeoutMs, int pageLimit, int previewLimit,
        int maxActiveRuns, String gitDirectory, long gitTimeoutMs) {
    public StorageProperties {
        new MetricWriterOptions(queueCapacity, batchSize, flushIntervalMs, maxAttempts, retryBackoffMs, shutdownTimeoutMs);
        if (root == null || root.isBlank() || pageLimit < 1 || previewLimit < 1 || previewLimit > pageLimit
                || maxActiveRuns < 1 || gitDirectory == null || gitTimeoutMs < 1) {
            throw new IllegalArgumentException("Invalid lab.storage configuration");
        }
    }
    public MetricWriterOptions writerOptions() {
        return new MetricWriterOptions(queueCapacity, batchSize, flushIntervalMs, maxAttempts, retryBackoffMs, shutdownTimeoutMs);
    }
}
