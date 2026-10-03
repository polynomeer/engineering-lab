package com.pnomeer.lab.core;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Explicit local spans, with a fixed reservation budget and no retained span list. */
public final class TraceRecorder implements AutoCloseable {
    public enum Stage { EXPERIMENT, IO_VIRTUAL_REQUEST, IO_REACTIVE_REQUEST }
    @FunctionalInterface public interface Operation<T> { T run(String spanId) throws Exception; }
    private final Consumer<MetricPoint> sink;
    private final String traceId;
    private final int maxSpans;
    private final AtomicLong reserved = new AtomicLong(), recorded = new AtomicLong(), dropped = new AtomicLong();
    private boolean closed;
    public TraceRecorder(Consumer<MetricPoint> sink, String traceId, int maxSpans) {
        if (maxSpans < 1 || maxSpans > 10000) throw new IllegalArgumentException("Trace span cap must be 1..10000");
        this.sink=sink; this.traceId=traceId; this.maxSpans=maxSpans;
    }
    public static TraceRecorder disabled() { return new TraceRecorder(null,"",1); }
    public <T> T call(Stage stage, String parentId, Operation<T> operation) throws Exception {
        if (sink == null) return operation.run(parentId);
        long number;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Trace recorder is closed");
            if (reserved.get() >= maxSpans) { dropped.incrementAndGet(); number=0; }
            else number=reserved.incrementAndGet();
        }
        if (number == 0) return operation.run(parentId);
        String spanId=Long.toString(number);
        long startedAt=System.currentTimeMillis(), started=System.nanoTime();
        T result;
        try { result=operation.run(spanId); }
        catch (Exception | Error ex) {
            try { emit(stage,parentId,spanId,startedAt,started,"FAILED"); }
            catch (RuntimeException | Error publication) { publication.addSuppressed(ex); throw publication; }
            throw ex;
        }
        emit(stage,parentId,spanId,startedAt,started,"SUCCEEDED");
        return result;
    }
    private synchronized void emit(Stage stage,String parentId,String spanId,long startedAt,long started,String status) {
        if (closed) throw new IllegalStateException("Span completed after trace recorder closed");
        long elapsed=System.nanoTime()-started;
        sink.accept(new MetricPoint(startedAt, Map.of(), Map.of("trace.durationNanos",elapsed),
                Map.of("source","trace.span","traceId",traceId,"spanId",spanId,"parentSpanId",parentId == null ? "" : parentId,
                        "stage",stage.name(),"status",status,"traceSchemaVersion","1")));
        recorded.incrementAndGet();
    }
    public Map<String,Long> counters() {
        return sink == null ? Map.of() : Map.of("trace.spans.recorded",recorded.get(),"trace.spans.dropped",dropped.get());
    }
    @Override public synchronized void close() { closed=true; }
}
