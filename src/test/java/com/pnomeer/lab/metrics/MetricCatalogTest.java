package com.pnomeer.lab.metrics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetricCatalogTest {
    @Test void distinguishesUnitsAndProcessScopeWithoutSuffixInference() {
        assertEquals("ns", MetricCatalog.unit("storage.writeNanos"));
        assertEquals("MiB", MetricCatalog.unit("resource.heapUsedMb"));
        assertEquals("rows/s", MetricCatalog.unit("throughput.insertedPerSec"));
        assertEquals("unknown", MetricCatalog.unit("newMetricMs"));
        assertTrue(MetricCatalog.definitions().stream().anyMatch(d -> d.name().equals("jvm.heap.usedBytes") && d.scope().equals("process")));
        assertThrows(UnsupportedOperationException.class, () -> MetricCatalog.definitions().clear());
    }
}
