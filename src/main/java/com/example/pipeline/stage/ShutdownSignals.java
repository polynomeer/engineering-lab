package com.example.pipeline.stage;

import com.example.pipeline.queue.BoundedChannel;

public final class ShutdownSignals {
    private ShutdownSignals() {
    }

    public static <T> void publishEndSignals(BoundedChannel<Envelope<T>> channel, int workerCount)
            throws InterruptedException {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("workerCount must be > 0");
        }
        for (int i = 0; i < workerCount; i++) {
            channel.put(Envelope.end());
        }
    }
}
