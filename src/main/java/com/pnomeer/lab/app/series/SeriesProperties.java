package com.pnomeer.lab.app.series;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Series run one repetition at a time; the queue bounds how many series may wait. */
@ConfigurationProperties("lab.series")
public record SeriesProperties(int queueCapacity, long runTimeoutMs, long pollIntervalMs) {
    public SeriesProperties {
        if (queueCapacity < 1 || queueCapacity > 32) throw new IllegalArgumentException("lab.series.queue-capacity must be 1..32");
        if (runTimeoutMs < 1000 || runTimeoutMs > 86_400_000) throw new IllegalArgumentException("lab.series.run-timeout-ms must be 1000..86400000");
        if (pollIntervalMs < 10 || pollIntervalMs > 10_000) throw new IllegalArgumentException("lab.series.poll-interval-ms must be 10..10000");
    }
}
