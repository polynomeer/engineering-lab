package com.pnomeer.lab.experiments.pipeline;

import com.pnomeer.lab.experiments.pipeline.queue.BoundedChannel;
import com.pnomeer.lab.experiments.pipeline.stage.Envelope;
import java.util.List;
import java.util.concurrent.*;

/** One drain deadline and one cleanup deadline, with deferred interrupt restoration. */
final class PipelineLifecycle {
    private final long timeoutMs;
    private Throwable failure;
    private boolean interrupted;
    PipelineLifecycle(long timeoutMs) { this.timeoutMs = timeoutMs; }
    long deadline() { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs); }
    void record(Throwable problem) {
        interrupted |= Thread.interrupted() || problem instanceof InterruptedException || failure == null && interruption(problem);
        if (failure == null) failure = problem;
        else if (failure != problem && failure.getSuppressed().length < 8) failure.addSuppressed(problem);
    }
    private static boolean interruption(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException || current instanceof java.io.InterruptedIOException) return true;
        }
        return false;
    }
    <T> void end(BoundedChannel<Envelope<T>> channel, int workers, long deadline) throws TimeoutException {
        for (int i = 0; i < workers;) {
            try { if (channel.offer(Envelope.end(), Math.min(100, remainingMillis(deadline)))) i++; }
            catch (InterruptedException ex) { record(ex); }
        }
    }
    void await(List<Future<?>> workers, long deadline) throws ExecutionException, TimeoutException {
        for (var worker : workers) {
            boolean done = false;
            while (!done) {
                try { worker.get(remainingMillis(deadline), TimeUnit.MILLISECONDS); done = true; }
                catch (InterruptedException ex) { record(ex); }
            }
        }
    }
    int stop(ExecutorService... pools) {
        if (Thread.interrupted()) { interrupted = true; record(new InterruptedException("Interrupted during cleanup")); }
        for (var pool : pools) if (!pool.isShutdown()) pool.shutdownNow();
        long deadline = deadline(); int remaining = 0;
        for (var pool : pools) {
            while (!pool.isTerminated()) {
                try { if (pool.awaitTermination(remainingMillis(deadline), TimeUnit.MILLISECONDS)) break; }
                catch (InterruptedException ex) { record(ex); }
                catch (TimeoutException ex) { break; }
            }
            if (!pool.isTerminated()) { pool.shutdownNow(); remaining++; }
        }
        if (remaining > 0) record(new TimeoutException("Pipeline executors did not terminate: " + remaining));
        return remaining;
    }
    boolean failed() { return failure != null; }
    Throwable failure() { return failure; }
    boolean interrupted() { return interrupted; }
    void restoreInterrupt() { if (interrupted) Thread.currentThread().interrupt(); }
    private static long remainingMillis(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("Pipeline drain/termination deadline exceeded");
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }
}
