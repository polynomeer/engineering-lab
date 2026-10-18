package com.pnomeer.lab.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Thread-confined, size-bounded batch. The owner chooses size/time/end-of-input flush triggers.
 * No executor, retries, transactions, row validation, metrics or lifecycle are owned here.
 */
public final class BoundedBatch<T> {
    @FunctionalInterface
    public interface Sink<T, E extends Exception> {
        /** Synchronous borrowed view: do not retain it or mutate the batch during this call. */
        void write(List<T> rows) throws E;
    }
    private final int capacity;
    private final ArrayList<T> rows;
    private final List<T> view;
    private boolean flushing;

    public BoundedBatch(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Batch capacity must be positive");
        this.capacity = capacity;
        rows = new ArrayList<>(capacity);
        view = Collections.unmodifiableList(rows);
    }
    public int size() { return rows.size(); }
    public boolean isEmpty() { return rows.isEmpty(); }
    public boolean isFull() { return rows.size() == capacity; }
    public void add(T row) {
        if (flushing || isFull()) throw new IllegalStateException("Batch is flushing or full");
        rows.add(row); // Validation and null semantics belong to the caller's domain.
    }
    /**
     * Empty batches do not call the sink. Failure retains all rows and propagates unchanged.
     * Retention is not permission to retry: the caller must resolve commit uncertainty.
     */
    public <E extends Exception> int flushTo(Sink<T, E> sink) throws E {
        Objects.requireNonNull(sink);
        if (flushing) throw new IllegalStateException("Reentrant batch flush");
        if (isEmpty()) return 0;
        int count = rows.size();
        flushing = true;
        try {
            sink.write(view);
            rows.clear();
            return count;
        } finally { flushing = false; }
    }
}
