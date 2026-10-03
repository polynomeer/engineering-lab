package com.pnomeer.lab.experiments.pipeline.queue;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedChannelTest {

    @Test
    void timedPollDoesNotCountTimeoutAsADequeueAndRemainsInterruptible() throws Exception {
        var channel = new BoundedChannel<String>(1, 100L);
        org.junit.jupiter.api.Assertions.assertNull(channel.poll(20, TimeUnit.MILLISECONDS));
        assertEquals(0, channel.getDequeuedCount());
        channel.put("row");
        assertEquals("row", channel.poll(1, TimeUnit.SECONDS));
        assertEquals(1, channel.getDequeuedCount());
        Thread.currentThread().interrupt();
        try { org.junit.jupiter.api.Assertions.assertThrows(InterruptedException.class, () -> channel.poll(1, TimeUnit.SECONDS)); }
        finally { Thread.interrupted(); }
        assertEquals(1, channel.getDequeuedCount());
    }

    @Test
    void offerTimesOutWhenQueueIsFull() throws Exception {
        var channel = new BoundedChannel<String>(1, 80L);
        channel.put("first");

        long start = System.nanoTime();
        boolean accepted = channel.offer("second");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertFalse(accepted);
        assertTrue(elapsedMs >= 50);
        assertEquals(1, channel.size());
        assertEquals(1, channel.getEnqueuedCount());
    }

    @Test
    void blockedProducerUnblocksAfterTake() throws Exception {
        var channel = new BoundedChannel<String>(1, 500L);
        channel.put("first");

        var started = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> {
                started.countDown();
                channel.put("second");
                return true;
            });

            assertTrue(started.await(1, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertEquals(1, channel.size());

            assertEquals("first", channel.take());
            assertTrue(future.get(1, TimeUnit.SECONDS));
            assertEquals("second", channel.take());
            assertEquals(2, channel.getEnqueuedCount());
            assertEquals(2, channel.getDequeuedCount());
            assertTrue(channel.getCumulativeEnqueueWaitNanos() > 0);
        } finally {
            executor.shutdownNow();
        }
    }
}
