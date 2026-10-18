package com.pnomeer.lab.experiments.examples;

import org.junit.jupiter.api.Test;
import com.pnomeer.lab.core.MetricPoint;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchedChecksumTest {
    @Test void streamsSameOutputAcrossBatchSizesIncludingTailAndLargeInput() throws Exception {
        for (int rows : List.of(1,23,10000)) for (int size : List.of(1,8,256)) {
            var points=new long[1];
            var result=new BatchedChecksumExperiment().execute(new BatchedChecksumExperiment.Scenario("test",rows,size,7,0),p -> points[0]++);
            assertEquals((long)rows,result.counters().get("checksum.rows"));
            assertEquals((long)rows*7+(long)rows*(rows-1)/2,result.counters().get("checksum.value"));
            assertEquals((rows+size-1)/size,points[0]);
            assertEquals((long)Math.min(rows,size),result.counters().get("checksum.maxBatchRows"));
        }
    }
    @Test void failingSinkDoesNotRetryOrEmitLaterBatchSuccess() {
        var points=new ArrayList<MetricPoint>();
        assertThrows(IllegalStateException.class,()->new BatchedChecksumExperiment().execute(new BatchedChecksumExperiment.Scenario("test",23,8,7,2),points::add));
        assertEquals(1,points.size()); assertEquals(8L,points.getFirst().counters().get("checksum.rows"));
    }
    @Test void interruptionAndMetricSinkFailureAreNotSwallowed() {
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class,()->new BatchedChecksumExperiment().execute(new BatchedChecksumExperiment.Scenario("test",23,8,7,0),p -> fail())); }
        finally { Thread.interrupted(); }
        var error=new IllegalStateException("evidence failure");
        assertSame(error,assertThrows(IllegalStateException.class,()->new BatchedChecksumExperiment().execute(new BatchedChecksumExperiment.Scenario("test",23,8,7,0),p -> {throw error;})));
        assertThrows(IllegalArgumentException.class,()->new BatchedChecksumExperiment.Scenario("test",10001,8,7,0));
    }
    @Test void additionalCaseComposesWithAllSixteenExistingProvidersAndContributions() {
        var providers=new ArrayList<com.pnomeer.lab.registry.ExperimentModule>();
        ServiceLoader.load(com.pnomeer.lab.registry.ExperimentModule.class).forEach(providers::add);
        providers.addAll(List.of(new com.pnomeer.lab.experiments.career.McpModuleConfiguration().mcpModule(true,true),
                new com.pnomeer.lab.experiments.career.EquityRegistrationConfiguration().equityModule(),
                new com.pnomeer.lab.experiments.career.MemoryRegistrationConfiguration().memoryModule(),
                new com.pnomeer.lab.experiments.career.MdsRegistrationConfiguration().mdsModule(),
                new com.pnomeer.lab.experiments.career.CreatorRegistrationConfiguration().creatorModule()));
        var example=new ChecksumConfiguration(); providers.add(example.checksumModule());
        var active=new com.pnomeer.lab.registry.ExperimentCatalog(providers);
        var patterns=new com.pnomeer.lab.registry.pattern.PatternCatalog(active,List.of(
                com.pnomeer.lab.registry.pattern.BuiltInPatternMetadata.contribution(),example.checksumPatterns()));
        var studies=new com.pnomeer.lab.registry.pattern.StudyCatalog(active,patterns,List.of(
                com.pnomeer.lab.registry.pattern.BuiltInStudies.contribution(),example.checksumStudies()));
        var measurements=new com.pnomeer.lab.registry.measurement.MeasurementCatalog(active,List.of(
                com.pnomeer.lab.registry.measurement.BuiltInMeasurements.contribution(),example.checksumMeasurements()));
        assertEquals(17,active.experiments().size());
        assertEquals(17,studies.snapshot().studies().size());
        assertEquals(17,measurements.snapshot().contracts().size());
        assertEquals("rows",com.pnomeer.lab.metrics.MetricCatalog.including(active).get("checksum.rows").unit());
    }

}
