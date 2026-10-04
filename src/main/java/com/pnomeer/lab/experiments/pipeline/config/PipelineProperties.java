package com.pnomeer.lab.experiments.pipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pipeline")
public class PipelineProperties {

    private long shutdownTimeoutMs = 30000;
    public long getShutdownTimeoutMs() { return shutdownTimeoutMs; }
    public void setShutdownTimeoutMs(long value) {
        if (value < 1 || value > 60000) throw new IllegalArgumentException("shutdownTimeoutMs must be 1..60000");
        shutdownTimeoutMs = value;
    }

    private int errorPreviewLimit = 100;
    public int getErrorPreviewLimit() { return errorPreviewLimit; }
    public void setErrorPreviewLimit(int value) {
        if (value < 0 || value > 1000) throw new IllegalArgumentException("errorPreviewLimit must be 0..1000");
        errorPreviewLimit = value;
    }

    private final Queue queue = new Queue();
    private final Threads threads = new Threads();
    private final Insert insert = new Insert();
    private final Backpressure backpressure = new Backpressure();

    public Queue getQueue() {
        return queue;
    }

    public Threads getThreads() {
        return threads;
    }

    public Insert getInsert() {
        return insert;
    }

    public Backpressure getBackpressure() {
        return backpressure;
    }

    public static class Queue {
        private int rawCapacity;
        private int mappedCapacity;

        public int getRawCapacity() {
            return rawCapacity;
        }

        public void setRawCapacity(int rawCapacity) {
            this.rawCapacity = rawCapacity;
        }

        public int getMappedCapacity() {
            return mappedCapacity;
        }

        public void setMappedCapacity(int mappedCapacity) {
            this.mappedCapacity = mappedCapacity;
        }
    }

    public static class Threads {
        private int validator;
        private int inserter;

        public int getValidator() {
            return validator;
        }

        public void setValidator(int validator) {
            this.validator = validator;
        }

        public int getInserter() {
            return inserter;
        }

        public void setInserter(int inserter) {
            this.inserter = inserter;
        }
    }

    public static class Insert {
        private int chunkSize;
        private long flushIntervalMs = 1000;
        private int maxAttempts = 3;
        private long retryBackoffMs = 100;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int value) {
            if (value < 1 || value > 10) throw new IllegalArgumentException("insert.maxAttempts must be 1..10");
            maxAttempts = value;
        }
        public long getRetryBackoffMs() { return retryBackoffMs; }
        public void setRetryBackoffMs(long value) {
            if (value < 1 || value > 60000) throw new IllegalArgumentException("insert.retryBackoffMs must be 1..60000");
            retryBackoffMs = value;
        }

        public long getFlushIntervalMs() { return flushIntervalMs; }
        public void setFlushIntervalMs(long flushIntervalMs) {
            if (flushIntervalMs < 1 || flushIntervalMs > 60000) {
                throw new IllegalArgumentException("insert.flushIntervalMs must be 1..60000");
            }
            this.flushIntervalMs = flushIntervalMs;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }
    }

    public static class Backpressure {
        private Long offerTimeoutMs;

        public Long getOfferTimeoutMs() {
            return offerTimeoutMs;
        }

        public void setOfferTimeoutMs(Long offerTimeoutMs) {
            this.offerTimeoutMs = offerTimeoutMs;
        }
    }
}
