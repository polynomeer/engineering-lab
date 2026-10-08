package com.pnomeer.lab.runtime;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ExecutionAdmissionTest {
    private ExperimentExecutors.Selection selection(String id, int limit, boolean exclusive) {
        return new ExperimentExecutors.Selection(id, mock(ExperimentExecutor.class), limit, exclusive);
    }

    @Test
    void countsRunsRatherThanThreadsAndAppliesGlobalAndExecutorLimits() {
        var admission = new ExecutionAdmission(2);
        var local = selection("local", 1, false);
        var worker = selection("worker", 2, false);
        try (var first = admission.acquire("first", local)) {
            assertThrows(ExecutionAdmission.Rejected.class, () -> admission.acquire("local-full", local));
            try (var second = admission.acquire("second", worker)) {
                assertThrows(ExecutionAdmission.Rejected.class, () -> admission.acquire("global-full", worker));
                assertThrows(IllegalStateException.class, () -> admission.acquire("first", worker));
            }
            try (var replacement = admission.acquire("replacement", worker)) {
                assertNotNull(replacement);
            }
        }
        try (var released = admission.acquire("released", local)) { assertNotNull(released); }
    }

    @Test
    void exclusiveAdmissionConflictsInBothDirectionsAndReleaseIsIdempotent() {
        var admission = new ExecutionAdmission(3);
        var shared = selection("shared", 3, false);
        var exclusive = selection("benchmark", 1, true);
        var old = admission.acquire("same-id", shared);
        assertThrows(ExecutionAdmission.Rejected.class, () -> admission.acquire("exclusive", exclusive));
        old.close();
        try (var current = admission.acquire("same-id", exclusive)) {
            old.close();
            assertThrows(ExecutionAdmission.Rejected.class, () -> admission.acquire("shared", shared));
        }
        try (var released = admission.acquire("shared", shared)) { assertNotNull(released); }
    }

    @Test
    void stopAcceptingAllowsPreparedWorkButCloseInterruptsActiveOwnersAndClosesAdmission() throws Exception {
        var admission = new ExecutionAdmission(2);
        var shared = selection("shared", 2, false);
        admission.stopAccepting();
        assertThrows(IllegalStateException.class, () -> admission.submit(() -> "late"));
        var entered = new CountDownLatch(1);
        var interrupted = new CompletableFuture<Boolean>();
        Thread owner = Thread.ofVirtual().start(() -> {
            try (var lease = admission.acquire("prepared", shared)) {
                entered.countDown();
                try { new CountDownLatch(1).await(); interrupted.complete(false); }
                catch (InterruptedException ex) { interrupted.complete(true); }
            } catch (Throwable ex) { interrupted.completeExceptionally(ex); }
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            admission.close(10);
            assertTrue(interrupted.get(2, TimeUnit.SECONDS));
            assertThrows(ExecutionAdmission.Rejected.class, () -> admission.acquire("late", shared));
        } finally { owner.interrupt(); owner.join(2000); }
        assertFalse(owner.isAlive());
    }

    @Test
    void invalidServerBindingsFailAtRegistrationInsteadOfFallingBack() {
        var registrations = Map.of("local", new ExperimentExecutors.Registration(mock(ExperimentExecutor.class), 2));
        assertThrows(IllegalArgumentException.class, () -> new ExperimentExecutors("missing", registrations, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ExperimentExecutors("local", registrations,
                Map.of("test.worker", new ExperimentExecutors.Binding("missing", false))));
        assertThrows(IllegalArgumentException.class, () -> new ExperimentExecutors.Registration(mock(ExperimentExecutor.class), 0));
    }
}
