package com.pnomeer.lab.metrics;

import org.junit.jupiter.api.Test;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JvmSnapshotTest {
    @Test void omitsUnsupportedValuesInsteadOfReportingZeroOrPartialGcTotals() {
        var unknown = mock(GarbageCollectorMXBean.class);
        when(unknown.getCollectionCount()).thenReturn(-1L);
        when(unknown.getCollectionTime()).thenReturn(-1L);
        var known = mock(GarbageCollectorMXBean.class);
        when(known.getCollectionCount()).thenReturn(10L);
        when(known.getCollectionTime()).thenReturn(20L);
        var result = JvmSnapshot.capture(new MemoryUsage(-1,10,20,-1), 1, List.of(known,unknown));
        assertFalse(result.gauges().containsKey("jvm.heap.maxBytes"));
        assertTrue(result.counters().isEmpty());
        assertEquals(10d, result.gauges().get("jvm.heap.usedBytes"));
    }
    @Test void recordsCaptureCostAndPayloadSizeWithoutAPlatformDependentSpeedThreshold() throws Exception {
        for (int i=0; i<20; i++) JvmSnapshot.capture();
        long total = 0, max = 0; int bytes = 0;
        var json = JsonMapper.builder().build();
        for (int i=0; i<100; i++) {
            long start = System.nanoTime(); var point = JvmSnapshot.capture(); long elapsed = System.nanoTime()-start;
            total += elapsed; max = Math.max(max,elapsed);
            bytes = Math.max(bytes,json.writeValueAsBytes(point).length);
        }
        assertTrue(bytes < 2048, "Fixed MXBean sample should remain under 2 KiB");
        Path report = Path.of("build/reports/observability/jvm-snapshot.json"); Files.createDirectories(report.getParent());
        Files.writeString(report,json.writeValueAsString(Map.of("samples",100,"meanCaptureNanos",total/100,
                "maxCaptureNanos",max,"maxPayloadBytes",bytes,"javaVersion",System.getProperty("java.version"),
                "note","Capture only, excludes publication, storage and application interference; not a latency guarantee")));
    }
}
