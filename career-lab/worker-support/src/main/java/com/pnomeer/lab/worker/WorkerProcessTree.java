package com.pnomeer.lab.worker;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Best-effort observation, never a replacement for OS process containment. */
final class WorkerProcessTree {
    static final int MAX_DESCENDANTS = 128;
    private final Process root;
    private final Map<ProcessHandle, Instant> descendants = new LinkedHashMap<>();
    private final Thread observer;
    private volatile boolean stopping;
    private boolean uncertain;

    WorkerProcessTree(Process root) {
        this.root = root;
        observer = new Thread(() -> {
            while (!stopping) {
                observe();
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        }, "worker-descendants");
        observer.setDaemon(true);
        observer.start();
    }

    private synchronized void observe() {
        try (var stream = root.descendants()) {
            var iterator = stream.iterator();
            // Bound traversal as well as retained identities, including repeated observations.
            for (int seen = 0; iterator.hasNext(); seen++) {
                if (seen >= MAX_DESCENDANTS) { uncertain = true; break; }
                var child = iterator.next();
                if (descendants.containsKey(child)) continue;
                if (descendants.size() >= MAX_DESCENDANTS) { uncertain = true; break; }
                var start = child.info().startInstant();
                if (start.isPresent()) descendants.put(child, start.get());
                else if (child.isAlive()) uncertain = true;
            }
        } catch (RuntimeException e) { uncertain = true; }
    }

    private synchronized boolean signal(boolean force) {
        observe();
        boolean alive = false;
        for (var entry : descendants.entrySet()) {
            try {
                var handle = entry.getKey();
                if (!handle.isAlive()) continue;
                var current = handle.info().startInstant();
                if (current.isEmpty()) { if (handle.isAlive()) uncertain = true; continue; }
                // A reused identity is not this worker's child and must not be signalled.
                if (!current.get().equals(entry.getValue())) continue;
                alive = true;
                if (force) handle.destroyForcibly(); else handle.destroy();
            } catch (RuntimeException e) { uncertain = true; }
        }
        if (root.isAlive()) {
            alive = true;
            try { if (force) root.destroyForcibly(); else root.destroy(); }
            catch (RuntimeException e) { uncertain = true; }
        }
        return alive;
    }

    /** Interrupts do not skip cleanup; restore the caller's flag after bounded waits. */
    boolean stop(Duration grace) {
        boolean interrupted = Thread.interrupted();
        stopping = true;
        observer.interrupt();
        try {
            long end = System.nanoTime() + grace.toNanos();
            while (observer.isAlive() && System.nanoTime() < end) {
                try { observer.join(10); } catch (InterruptedException e) { interrupted = true; }
            }
            if (observer.isAlive()) {
                // Do not leave the direct worker running if observation itself is stuck.
                try { root.destroyForcibly(); } catch (RuntimeException ignored) {}
                return false;
            }
            for (boolean force : new boolean[]{false, true}) {
                end = System.nanoTime() + grace.toNanos();
                do {
                    if (!signal(force)) return !uncertain;
                    try { TimeUnit.MILLISECONDS.sleep(10); } catch (InterruptedException e) { interrupted = true; }
                } while (System.nanoTime() < end);
            }
            return !signal(true) && !uncertain;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
