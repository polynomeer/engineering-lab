package com.portfolio.mcplab.worker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkerCancellationTest {
    @AfterEach void clearInterrupt() { Thread.interrupted(); }

    @Test void consumedInterruptStillPreventsSuccessfulCompletion() {
        var cancellation = new WorkerCancellation(Thread.currentThread());
        assertTrue(cancellation.request("PARENT_CONNECTION_LOST"));
        assertTrue(Thread.interrupted()); // Simulate a JDBC/container library consuming the flag.
        assertThrows(InterruptedException.class, cancellation::check);
        assertEquals("PARENT_CONNECTION_LOST", cancellation.finish());
    }

    @Test void repeatedRequestsKeepFirstReasonAndStartOnlyOneFallback() {
        var cancellation = new WorkerCancellation(Thread.currentThread());
        assertTrue(cancellation.request("DEADLINE_EXCEEDED"));
        Thread.interrupted();
        assertFalse(cancellation.request("PARENT_CONNECTION_LOST"));
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals("DEADLINE_EXCEEDED", cancellation.finish());
    }

    @Test void connectionCloseAfterCleanupCannotRevokeCompletion() throws Exception {
        var cancellation = new WorkerCancellation(Thread.currentThread());
        cancellation.check();
        assertNull(cancellation.finish());
        assertTrue(cancellation.finished());
        assertFalse(cancellation.request("PARENT_CONNECTION_LOST"));
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
