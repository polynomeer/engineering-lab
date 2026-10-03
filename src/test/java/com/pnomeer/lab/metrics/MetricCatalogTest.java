package com.pnomeer.lab.metrics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetricCatalogTest {
    @Test void declaresAggregationIndependentlyOfLegacyContainer() {
        var definitions = MetricCatalog.definitions().stream().collect(java.util.stream.Collectors.toMap(MetricCatalog.Definition::name, d -> d));
        assertEquals("INTERVAL_RATE", definitions.get("throughput.insertedPerSec").semantics());
        assertEquals("RUN_MEAN", definitions.get("virtual.avgLatencyMs").semantics());
        assertEquals("PROCESS_CUMULATIVE", definitions.get("jvm.gc.collectionTimeMs").semantics());
        assertTrue(definitions.values().stream().allMatch(d -> !d.semantics().equals("UNKNOWN")));
    }
    @Test void distinguishesUnitsAndProcessScopeWithoutSuffixInference() {
        assertEquals("ns", MetricCatalog.unit("storage.writeNanos"));
        assertEquals("MiB", MetricCatalog.unit("resource.heapUsedMb"));
        assertEquals("rows/s", MetricCatalog.unit("throughput.insertedPerSec"));
        assertEquals("unknown", MetricCatalog.unit("newMetricMs"));
        assertTrue(MetricCatalog.definitions().stream().anyMatch(d -> d.name().equals("jvm.heap.usedBytes") && d.scope().equals("process")));
        assertThrows(UnsupportedOperationException.class, () -> MetricCatalog.definitions().clear());
    }
}
