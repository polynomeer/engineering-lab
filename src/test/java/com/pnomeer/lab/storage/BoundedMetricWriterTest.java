package com.pnomeer.lab.storage;

import com.pnomeer.lab.core.MetricPoint;
import com.pnomeer.lab.core.storage.RunStore;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BoundedMetricWriterTest {
    private static final MetricPoint POINT = new MetricPoint(1, Map.of(), Map.of(), Map.of());
    private MetricWriterOptions options(int batch, long interval) { return new MetricWriterOptions(2, batch, interval, 3, 1, 2000); }

    @Test
    void flushesBySizeTimeAndCloseAndRetriesWithoutChangingSequences() throws Exception {
        RunStore store = mock(RunStore.class);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch flushed = new CountDownLatch(1);
        var attempts = new java.util.concurrent.CopyOnWriteArrayList<java.util.List<com.pnomeer.lab.core.storage.StoredMetric>>();
        doAnswer(invocation -> {
            attempts.add(java.util.List.copyOf(invocation.getArgument(1)));
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("transient");
            flushed.countDown();
            return null;
        }).when(store).appendMetrics(anyString(), anyList());
        try (var writer = new BoundedMetricWriter(store, "size", options(2, 10000))) {
            writer.accept(POINT); writer.accept(POINT);
            assertTrue(flushed.await(2, TimeUnit.SECONDS));
        }
        assertEquals(2, calls.get());
        assertEquals(attempts.getFirst(), attempts.getLast());
        assertEquals(java.util.List.of(0L, 1L), attempts.getFirst().stream().map(com.pnomeer.lab.core.storage.StoredMetric::sequence).toList());
        CountDownLatch timed = new CountDownLatch(1);
        doAnswer(i -> { timed.countDown(); return null; }).when(store).appendMetrics(eq("time"), anyList());
        try (var writer = new BoundedMetricWriter(store, "time", options(10, 30))) {
            writer.accept(POINT);
            assertTrue(timed.await(2, TimeUnit.SECONDS));
        }
        AtomicInteger partialSize = new AtomicInteger();
        doAnswer(i -> { partialSize.set(((java.util.List<?>)i.getArgument(1)).size()); return null; })
                .when(store).appendMetrics(eq("close"), anyList());
        try (var writer = new BoundedMetricWriter(store, "close", options(10, 10000))) { writer.accept(POINT); }
        assertEquals(1, partialSize.get());
    }

    @Test
    void blocksProducerAtCapacityAndDrainsWithoutLoss() throws Exception {
        RunStore store = mock(RunStore.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), published = new CountDownLatch(1);
        AtomicInteger written = new AtomicInteger();
        doAnswer(i -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            written.addAndGet(((java.util.List<?>)i.getArgument(1)).size());
            return null;
        }).when(store).appendMetrics(anyString(), anyList());
        try (var writer = new BoundedMetricWriter(store, "bounded", options(1, 10000))) {
            writer.accept(POINT);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            writer.accept(POINT); writer.accept(POINT);
            Thread producer = Thread.ofVirtual().start(() -> { writer.accept(POINT); published.countDown(); });
            try {
                assertFalse(published.await(80, TimeUnit.MILLISECONDS));
                assertEquals(2L, writer.counters().get("storage.queue.depth"));
            } finally { release.countDown(); }
            assertTrue(published.await(1, TimeUnit.SECONDS));
            producer.join(1000);
        }
        assertEquals(4, written.get());
    }

    @Test
    void failsExplicitlyAfterBoundedAttempts() {
        RunStore store = mock(RunStore.class);
        doThrow(new IllegalStateException("offline")).when(store).appendMetrics(anyString(), anyList());
        var writer = new BoundedMetricWriter(store, "failed", options(10, 10000));
        writer.accept(POINT);
        assertThrows(IllegalStateException.class, writer::close);
        assertThrows(IllegalStateException.class, () -> writer.accept(POINT));
        verify(store, times(3)).appendMetrics(eq("failed"), anyList());
        assertEquals(1L, writer.counters().get("storage.failures"));
        assertEquals(2L, writer.counters().get("storage.retries"));
    }

    @Test
    void boundsShutdownTimeWhenStorageDoesNotFinish() throws Exception {
        RunStore store = mock(RunStore.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(i -> { entered.countDown(); release.await(); return null; })
                .when(store).appendMetrics(anyString(), anyList());
        var writer = new BoundedMetricWriter(store, "slow", new MetricWriterOptions(1,1,10,1,0,50));
        writer.accept(POINT);
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        try {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),
                    () -> assertThrows(IllegalStateException.class, writer::close));
        } finally { release.countDown(); }
    }
}
