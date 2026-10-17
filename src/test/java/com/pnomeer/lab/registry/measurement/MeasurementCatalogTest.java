package com.pnomeer.lab.registry.measurement;

import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.experiments.career.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.pnomeer.lab.registry.measurement.MeasurementContract.*;

class MeasurementCatalogTest {
    @Test void coversAllSixteenExperimentsWithBoundedImmutableRoundTrips() {
        var providers = new ArrayList<ExperimentModule>(); ServiceLoader.load(ExperimentModule.class).forEach(providers::add);
        providers.addAll(List.of(new McpModuleConfiguration().mcpModule(true,true), new EquityRegistrationConfiguration().equityModule(),
                new MemoryRegistrationConfiguration().memoryModule(), new MdsRegistrationConfiguration().mdsModule(), new CreatorRegistrationConfiguration().creatorModule()));
        var catalog = new MeasurementCatalog(new ExperimentCatalog(providers), List.of(BuiltInMeasurements.contribution()));
        assertEquals(16, catalog.snapshot().contracts().size());
        for (var c : catalog.snapshot().contracts()) {
            assertEquals(c, read(catalog.parameters(c.experimentId()).get(PARAMETER), c.experimentId()).orElseThrow());
            assertFalse(c.properties().isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> c.measures().clear());
        }
        assertEquals(Map.of(), catalog.parameters("custom.unclassified"));
        assertEquals(3, new MeasurementCatalog(ExperimentCatalog.load(), List.of(BuiltInMeasurements.contribution())).snapshot().contracts().size());
    }
    @Test void refusesConflictingRegistrationsPropertiesAndUnboundedSnapshots() {
        var c = BuiltInMeasurements.contribution().contracts().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new MeasurementCatalog(ExperimentCatalog.load(),List.of(BuiltInMeasurements.contribution(), new MeasurementContribution(List.of(c)))));
        var conflict = new MeasurementContract(1,"custom.new","1.0.0",List.of(new Property(c.properties().getFirst().id(),"Other meaning","Other evidence")),List.of(),List.of(),"Limit");
        assertThrows(IllegalArgumentException.class, () -> new MeasurementCatalog(ExperimentCatalog.load(),List.of(BuiltInMeasurements.contribution(),new MeasurementContribution(List.of(conflict)))));
        assertThrows(IllegalArgumentException.class, () -> new MeasurementContract(1,"x","1",List.of(),List.of(),List.of("seed","seed"),"Limit"));
        assertThrows(IllegalArgumentException.class, () -> new MeasurementContribution(Collections.nCopies(257,c)));
        var large = new ArrayList<Property>();
        for (int i=0;i<16;i++) large.add(new Property("property-"+i,"가".repeat(1000),"evidence"));
        assertThrows(IllegalArgumentException.class, () -> new MeasurementContract(1,"x","1",large,List.of(),List.of(),"limit").encode());
    }
    @Test void neverInfersOldMalformedFutureOrWrongExperimentContracts() {
        var c = BuiltInMeasurements.contribution().contracts().getFirst();
        for (String value : Arrays.asList(null, "{}", "null", "bad", "x".repeat(MAX_BYTES+1), c.encode().replace("\"schemaVersion\":1","\"schemaVersion\":2")))
            assertTrue(read(value,c.experimentId()).isEmpty());
        assertTrue(read(c.encode(),"other.experiment").isEmpty());
    }
    @Test void discoversIndependentSpringContributionsWithoutBlockingUnclassifiedExperiments() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            var active = new ExperimentCatalog(List.of(new ExperimentModule() {
                public ModuleDescriptor module() { return new ModuleDescriptor("custom","Custom","examples"); }
                public List<ExperimentDescriptor> experiments() { return List.of(
                        new ExperimentDescriptor("custom.demo","Demo","CUSTOM","NONE",Map.of(),List.of("SINGLE_PASS")),
                        new ExperimentDescriptor("custom.no-contract","No contract","CUSTOM","NONE",Map.of(),List.of("SINGLE_PASS"))); }
            }));
            context.registerBean(ExperimentCatalog.class, () -> active);
            context.registerBean("customMeasurements", MeasurementContribution.class, () -> new MeasurementContribution(List.of(
                    new MeasurementContract(1,"custom.demo","1.0.0",List.of(new Property("custom-property","Meaning","Evidence")),List.of(),List.of(),"Limit"))));
            context.register(com.pnomeer.lab.registry.pattern.PatternConfiguration.class);
            context.refresh();
            var catalog = context.getBean(MeasurementCatalog.class);
            assertEquals("custom.demo",catalog.snapshot().contracts().getFirst().experimentId());
            assertEquals(1,catalog.snapshot().contracts().size());
            assertEquals(Map.of(),catalog.parameters("custom.no-contract"));
        }
    }
}
