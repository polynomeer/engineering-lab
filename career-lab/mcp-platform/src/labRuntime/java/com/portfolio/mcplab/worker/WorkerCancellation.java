package com.portfolio.mcplab.worker;

/** Keeps cancellation even when a library consumes the thread's interrupt flag. */
final class WorkerCancellation {
    private final Thread owner;
    private String reason;
    private boolean finished;

    WorkerCancellation(Thread owner) { this.owner = owner; }

    /** The first request owns the reason and starts the single forced-stop fallback. */
    synchronized boolean request(String reason) {
        if (finished || this.reason != null) return false;
        this.reason = reason;
        owner.interrupt();
        return true;
    }

    synchronized void check() throws InterruptedException {
        if (reason != null) throw new InterruptedException(reason);
    }

    /** Linearization point after cleanup: later parent EOF cannot revoke a completed outcome. */
    synchronized String finish() {
        finished = true;
        return reason;
    }

    synchronized boolean finished() { return finished; }
}
