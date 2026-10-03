package com.pnomeer.lab.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TraceRecorderTest {
    @Test void reservesRootAndCapsChildrenWithoutSkippingWork() throws Exception {
        var points = new ArrayList<MetricPoint>();
        var trace = new TraceRecorder(points::add,"run",3);
        int result = trace.call(TraceRecorder.Stage.EXPERIMENT,null,root -> {
            int count=0;
            for (int i=0;i<10;i++) count+=trace.call(TraceRecorder.Stage.IO_VIRTUAL_REQUEST,root,span -> 1);
            return count;
        });
        trace.close();
        assertEquals(10,result); assertEquals(3,points.size());
        assertEquals("1",points.getFirst().labels().get("parentSpanId"));
        assertEquals("EXPERIMENT",points.getLast().labels().get("stage"));
        assertEquals("",points.getLast().labels().get("parentSpanId"));
        assertEquals(8L,trace.counters().get("trace.spans.dropped"));
        assertTrue(points.stream().allMatch(p -> p.counters().get("trace.durationNanos")>=0));
        assertThrows(IllegalStateException.class, () -> trace.call(TraceRecorder.Stage.EXPERIMENT,null,id -> 1));
    }
    @Test void recordsFailureWithoutSensitiveExceptionContentAndPropagatesSinkFailure() {
        var points=new ArrayList<MetricPoint>();
        var trace=new TraceRecorder(points::add,"run",1);
        var original=new IllegalArgumentException("secret input");
        assertSame(original,assertThrows(IllegalArgumentException.class, () -> trace.call(TraceRecorder.Stage.EXPERIMENT,null,id -> { throw original; })));
        assertEquals("FAILED",points.getFirst().labels().get("status"));
        assertFalse(points.toString().contains("secret"));
        var broken=new TraceRecorder(p -> { throw new IllegalStateException("storage failed"); },"run",1);
        var failure=assertThrows(IllegalStateException.class, () -> broken.call(TraceRecorder.Stage.EXPERIMENT,null,id -> { throw original; }));
        assertSame(original,failure.getSuppressed()[0]);
    }
    @Test void reportsBoundedPayloadAndCollectionCost() throws Exception {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var largest = new java.util.concurrent.atomic.AtomicInteger();
        var trace = new TraceRecorder(point -> largest.accumulateAndGet(json.writeValueAsBytes(point).length, Math::max),
                "00000000-0000-0000-0000-000000000000", 1000);
        long started = System.nanoTime();
        for (int i = 0; i < 1000; i++) trace.call(TraceRecorder.Stage.EXPERIMENT, null, id -> null);
        long elapsed = System.nanoTime() - started;
        trace.close();
        assertEquals(1000L, trace.counters().get("trace.spans.recorded"));
        assertTrue(largest.get() < 1024);
        var report = java.nio.file.Path.of("build/reports/observability/trace.json");
        java.nio.file.Files.createDirectories(report.getParent());
        java.nio.file.Files.writeString(report, json.writeValueAsString(Map.of(
                "spans", 1000, "elapsedNanosIncludingJson", elapsed,
                "maxPayloadBytes", largest.get(), "sink", "JSON serialization without DB")));
    }
    @Test void disabledRecorderDoesNotReserveSpans() throws Exception {
        var recorder=TraceRecorder.disabled();
        int result = recorder.call(TraceRecorder.Stage.EXPERIMENT,null,id -> 10);
        assertEquals(10, result);
        assertTrue(recorder.counters().isEmpty());
    }
}
