package com.pnomeer.lab.pipeline;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BoundedBatchTest {
    @Test void enforcesCapacityAndFlushesFullAndPartialBatchesInOrder() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBatch<>(0));
        var batch = new BoundedBatch<Integer>(3);
        var result = new ArrayList<List<Integer>>();
        for (int i=0;i<8;i++) {
            batch.add(i);
            if (batch.isFull()) {
                assertThrows(IllegalStateException.class, () -> batch.add(99));
                assertEquals(3,batch.flushTo(rows -> result.add(List.copyOf(rows))));
            }
        }
        assertEquals(2,batch.flushTo(rows -> result.add(List.copyOf(rows))));
        assertEquals(List.of(List.of(0,1,2),List.of(3,4,5),List.of(6,7)),result);
        assertEquals(0,batch.flushTo(rows -> fail("Empty batch must not write")));
        assertTrue(batch.isEmpty());
    }
    @Test void failureRetainsRowsWithoutAutomaticRetryOrExceptionTranslation() throws Exception {
        var batch = new BoundedBatch<Integer>(2); batch.add(1); batch.add(2);
        var failure = new IOException("commit outcome unknown");
        assertSame(failure,assertThrows(IOException.class, () -> batch.flushTo(rows -> { throw failure; })));
        assertTrue(batch.isFull());
        assertEquals(2,batch.size());
        // Explicit inspection after failure; recovery policy belongs to the owner.
        batch.flushTo(rows -> assertEquals(List.of(1,2),rows));
        batch.add(3);
        var interrupted = new InterruptedException("stop");
        assertSame(interrupted,assertThrows(InterruptedException.class, () -> batch.flushTo(rows -> { throw interrupted; })));
        assertEquals(1,batch.size());
    }
    @Test void borrowedViewCannotMutateAndReentrantWritesCannotLoseRows() {
        var batch = new BoundedBatch<String>(2); batch.add("a");
        batch.flushTo(rows -> {
            assertThrows(UnsupportedOperationException.class, () -> rows.clear());
            assertThrows(IllegalStateException.class, () -> batch.add("b"));
            assertThrows(IllegalStateException.class, () -> batch.flushTo(ignored -> {}));
            assertEquals(List.of("a"),rows);
        });
        assertTrue(batch.isEmpty());
    }
}
