package com.pnomeer.pipeline.stage;

import com.pnomeer.pipeline.queue.BoundedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageShutdownTest {

    @Test
    void publishEndSignalsRequiresPositiveWorkerCount() {
        var channel = new BoundedChannel<Envelope<Integer>>(4, 100L);
        assertThrows(IllegalArgumentException.class, () -> ShutdownSignals.publishEndSignals(channel, 0));
    }

    @Test
    void oneEndSignalPerWorkerAllowsGracefulShutdown() throws Exception {
        var channel = new BoundedChannel<Envelope<Integer>>(16, 100L);
        channel.put(Envelope.data(10));
        channel.put(Envelope.data(20));
        channel.put(Envelope.data(30));

        int workers = 2;
        ShutdownSignals.publishEndSignals(channel, workers);

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    int processed = 0;
                    while (true) {
                        Envelope<Integer> envelope = channel.take();
                        if (envelope.isEnd()) {
                            return processed;
                        }
                        processed++;
                    }
                }));
            }

            int totalProcessed = 0;
            for (Future<Integer> future : futures) {
                totalProcessed += future.get();
            }

            assertEquals(3, totalProcessed);
            assertEquals(0, channel.size());
            assertEquals(5, channel.getEnqueuedCount());
            assertEquals(5, channel.getDequeuedCount());
            assertTrue(channel.getCumulativeEnqueueWaitNanos() > 0);
        } finally {
            executor.shutdownNow();
        }
    }
}
