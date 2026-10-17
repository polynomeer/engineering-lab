package com.pnomeer.lab.registry.pattern;

import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.experiments.career.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.*;
import java.util.stream.IntStream;
import static com.pnomeer.lab.registry.pattern.PatternContribution.*;
import static org.junit.jupiter.api.Assertions.*;

class PatternCatalogTest {
    private static PatternCatalog catalog(PatternContribution... contributions) {
        return new PatternCatalog(ExperimentCatalog.load(), List.of(contributions));
    }
    private static Pattern pattern(String id, String category) {
        return new Pattern(id, category, "Pattern", "Problem", "Observable property", "Limited scope");
    }
    private static PatternContribution extra(String experiment) {
        return new PatternContribution(List.of(new Category("extra", "Extra")),
                List.of(pattern("extra-pattern", "extra")), List.of(new Binding(experiment,
                new Classification("extra", List.of("extra-pattern")))));
    }
    private static ExperimentModule module(String id) {
        return new ExperimentModule() {
            public ModuleDescriptor module() { return new ModuleDescriptor("extension", "Extension", "examples"); }
            public List<ExperimentDescriptor> experiments() {
                return List.of(new ExperimentDescriptor(id, "New experiment", "CUSTOM", "NONE", Map.of(), List.of("SINGLE_PASS")));
            }
        };
    }
    @Test void defaultServerDoesNotAdvertiseInactiveCareerExperiments() {
        var active = ExperimentCatalog.load();
        var descriptors = active.experiments();
        var snapshot = new PatternCatalog(active, List.of(BuiltInPatternMetadata.contribution())).snapshot();
        assertEquals(7, snapshot.categories().size());
        assertEquals(18, snapshot.patterns().size());
        assertEquals(List.of("concurrency.queue-contention", "io.endpoint-comparison", "pipeline.excel-ingestion"),
                snapshot.experiments().stream().map(PatternCatalog.ExperimentView::experimentId).toList());
        assertTrue(snapshot.experiments().stream().allMatch(e -> e.classification() != null));
        assertEquals(descriptors, active.experiments());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.experiments().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.experiments().getFirst().classification().patternIds().clear());
    }
    @Test void allRealProvidersKeepTheirIdsAndHaveExactlyOnePrimaryClassification() {
        var providers = new ArrayList<ExperimentModule>();
        ServiceLoader.load(ExperimentModule.class).forEach(providers::add);
        providers.addAll(List.of(new McpModuleConfiguration().mcpModule(true, true),
                new EquityRegistrationConfiguration().equityModule(), new MemoryRegistrationConfiguration().memoryModule(),
                new MdsRegistrationConfiguration().mdsModule(), new CreatorRegistrationConfiguration().creatorModule()));
        var active = new ExperimentCatalog(providers);
        var snapshot = new PatternCatalog(active, List.of(BuiltInPatternMetadata.contribution())).snapshot();
        assertEquals(16, snapshot.experiments().size());
        assertEquals(new TreeSet<>(active.experiments().stream().map(ExperimentDescriptor::id).toList()),
                new TreeSet<>(snapshot.experiments().stream().map(PatternCatalog.ExperimentView::experimentId).toList()));
        assertEquals(new TreeSet<>(snapshot.experiments().stream().map(PatternCatalog.ExperimentView::experimentId).toList()),
                new TreeSet<>(BuiltInPatternMetadata.contribution().bindings().stream().map(Binding::experimentId).toList()));
        assertEquals(13, snapshot.experiments().stream().filter(e -> e.sourceCollection().equals("career")).count());
        assertTrue(snapshot.experiments().stream().allMatch(e -> e.classification() != null));
        var registration = snapshot.experiments().stream().filter(e -> e.experimentId().endsWith(".registration")).findFirst().orElseThrow();
        assertEquals("equity-system", registration.sourceModuleId());
        assertEquals("data-processing", registration.classification().categoryId());
        assertTrue(registration.classification().patternIds().contains("bounded-queue"));
    }
    @Test void springDiscoversNewContributionsWithoutChangingControllerOrLegacyProviders() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ExperimentCatalog.class, () -> new ExperimentCatalog(List.of(module("extension.demo"))));
            context.registerBean("extensionPatterns", PatternContribution.class, () -> extra("extension.demo"));
            context.register(PatternConfiguration.class); context.refresh();
            var view = context.getBean(PatternCatalog.class).snapshot().experiments().getFirst();
            assertEquals("extra", view.classification().categoryId());
            assertEquals("examples", view.sourceCollection());
        }
        var unknown = new PatternCatalog(new ExperimentCatalog(List.of(module("extension.new"))),
                List.of(BuiltInPatternMetadata.contribution())).snapshot().experiments().getFirst();
        assertNull(unknown.classification());
    }
    @Test void rejectsDuplicatesAndDanglingReferencesEvenForInactiveExperiments() {
        assertThrows(IllegalArgumentException.class, () -> catalog(extra("inactive.one"), extra("inactive.two")));
        var category = new Category("extra", "Extra");
        var definition = new PatternContribution(List.of(category), List.of(pattern("p", "extra")), List.of());
        assertThrows(IllegalArgumentException.class, () -> catalog(definition,
                new PatternContribution(List.of(), List.of(pattern("p", "extra")), List.of())));
        assertThrows(IllegalArgumentException.class, () -> catalog(extra("inactive.one"),
                new PatternContribution(List.of(), List.of(), extra("inactive.one").bindings())));
        assertThrows(IllegalArgumentException.class, () -> catalog(new PatternContribution(List.of(), List.of(pattern("p", "missing")), List.of())));
        assertThrows(IllegalArgumentException.class, () -> catalog(definition, new PatternContribution(List.of(), List.of(),
                List.of(new Binding("inactive.one", new Classification("extra", List.of("absent")))))));
        assertThrows(IllegalArgumentException.class, () -> catalog(definition, new PatternContribution(List.of(), List.of(),
                List.of(new Binding("inactive.one", new Classification("missing", List.of("p")))))));
        assertThrows(IllegalArgumentException.class, () -> catalog(definition, new PatternContribution(List.of(new Category("other", "Other")), List.of(),
                List.of(new Binding("inactive.one", new Classification("other", List.of("p")))))));
    }
    @Test void rejectsMalformedMetadataAndBoundsBothIndividualAndMergedContributions() {
        assertThrows(IllegalArgumentException.class, () -> new Category("../escape", "Bad"));
        assertThrows(IllegalArgumentException.class, () -> new Category("valid", "x".repeat(201)));
        assertThrows(IllegalArgumentException.class, () -> new Category("valid", "line\nbreak"));
        assertThrows(IllegalArgumentException.class, () -> new Classification("valid", List.of("p", "p")));
        assertThrows(IllegalArgumentException.class, () -> new Classification("valid", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PatternContribution(Collections.nCopies(129, new Category("valid", "Title")), List.of(), List.of()));
        var categories = IntStream.range(0, 128).mapToObj(i -> new Category("c" + i, "Category")).toList();
        assertThrows(IllegalArgumentException.class, () -> catalog(new PatternContribution(categories, List.of(), List.of()), extra("inactive.one")));
        assertThrows(IllegalArgumentException.class, () -> new PatternCatalog(ExperimentCatalog.load(), Collections.nCopies(257, extra("inactive.one"))));
    }
    @Test void defensiveCopiesAndOrderAreStableAcrossProviderOrder() {
        var categories = new ArrayList<>(List.of(new Category("z", "Last"), new Category("a", "First")));
        var contribution = new PatternContribution(categories, List.of(), List.of());
        categories.clear();
        assertEquals(List.of("a", "z"), catalog(contribution).snapshot().categories().stream().map(Category::id).toList());
        var first = new PatternContribution(List.of(new Category("a", "First")), List.of(), List.of());
        var second = new PatternContribution(List.of(new Category("z", "Last")), List.of(), List.of());
        assertEquals(catalog(first, second).snapshot(), catalog(second, first).snapshot());
    }
}
