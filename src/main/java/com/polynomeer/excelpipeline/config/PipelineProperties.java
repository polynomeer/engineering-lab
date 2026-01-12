package com.polynomeer.excelpipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pipeline")
public class PipelineProperties {

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
