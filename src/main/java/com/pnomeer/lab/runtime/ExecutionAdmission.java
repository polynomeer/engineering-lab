package com.pnomeer.lab.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One bounded active-run ledger shared by all execution kinds. No additional waiting queue. */
final class ExecutionAdmission {
    private static final Logger log = LoggerFactory.getLogger(ExecutionAdmission.class);
    static final class Rejected extends IllegalStateException {
        Rejected(String reason) { super(reason); }
    }
    private final int maxActiveRuns;
    private final Map<String, Lease> active = new HashMap<>();
    private boolean accepting = true;
    private boolean closing;

    ExecutionAdmission(int maxActiveRuns) {
        if (maxActiveRuns < 1) throw new IllegalArgumentException("Run limit must be positive");
        this.maxActiveRuns = maxActiveRuns;
    }

    synchronized void checkAccepting() {
        if (!accepting) throw new IllegalStateException("Experiment runner is shutting down");
    }

    synchronized <T> T submit(Supplier<T> create) {
        checkAccepting();
        return create.get();
    }

    /** Keep rejection persistence atomic with admission so a competing caller cannot start this Run. */
    synchronized Lease acquire(String runId, ExperimentExecutors.Selection selection, Consumer<String> reject) {
        try { return acquire(runId, selection); }
        catch (Rejected ex) {
            reject.accept(ex.getMessage());
            throw ex;
        }
    }

    synchronized Lease acquire(String runId, ExperimentExecutors.Selection selection) {
        // A duplicate must not turn the already running owner's Run into FAILED.
        if (active.containsKey(runId)) throw new IllegalStateException("Run is already executing: " + runId);
        if (closing || active.size() >= maxActiveRuns) {
            throw new Rejected("Execution admission limit or shutdown");
        }
        if (active.values().stream().anyMatch(lease -> lease.exclusive)
                || selection.exclusive() && !active.isEmpty()) {
            throw new Rejected("Exclusive execution admission conflict");
        }
        long executorRuns = active.values().stream()
                .filter(lease -> lease.executorId.equals(selection.executorId())).count();
        if (executorRuns >= selection.maxActiveRuns()) throw new Rejected("Executor admission limit: " + selection.executorId());
        var lease = new Lease(runId, selection.executorId(), selection.exclusive(), Thread.currentThread());
        active.put(runId, lease);
        return lease;
    }

    synchronized void stopAccepting() { accepting = false; }

    synchronized void close(long timeoutMs) {
        accepting = false;
        closing = true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!active.isEmpty()) {
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (left <= 0) break;
            try { wait(left); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
        }
        if (!active.isEmpty()) {
            log.error("stage=run-storage reason=shutdown_timeout active_runs={}", active.size());
            active.values().forEach(lease -> lease.thread.interrupt());
        }
    }

    final class Lease implements AutoCloseable {
        private final String runId, executorId;
        private final boolean exclusive;
        private final Thread thread;
        private Lease(String runId, String executorId, boolean exclusive, Thread thread) {
            this.runId = runId; this.executorId = executorId; this.exclusive = exclusive; this.thread = thread;
        }
        @Override public void close() {
            synchronized (ExecutionAdmission.this) {
                active.remove(runId, this);
                ExecutionAdmission.this.notifyAll();
            }
        }
    }
}
