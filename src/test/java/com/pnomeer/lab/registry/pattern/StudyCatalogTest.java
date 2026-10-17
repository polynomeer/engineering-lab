package com.pnomeer.lab.registry.pattern;

import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.experiments.career.*;
import com.pnomeer.lab.scenario.*;
import com.pnomeer.lab.app.run.StudyCatalogController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.util.*;
import static com.pnomeer.lab.registry.pattern.StudyContribution.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StudyCatalogTest {
    private ExperimentCatalog all() {
        var providers = new ArrayList<ExperimentModule>(); ServiceLoader.load(ExperimentModule.class).forEach(providers::add);
        providers.addAll(List.of(new McpModuleConfiguration().mcpModule(true,true),new EquityRegistrationConfiguration().equityModule(),
                new MemoryRegistrationConfiguration().memoryModule(),new MdsRegistrationConfiguration().mdsModule(),new CreatorRegistrationConfiguration().creatorModule()));
        return new ExperimentCatalog(providers);
    }
    private StudyCatalog catalog(ExperimentCatalog active, StudyContribution... contributions) {
        return new StudyCatalog(active, new PatternCatalog(active,List.of(BuiltInPatternMetadata.contribution())),List.of(contributions));
    }
    @Test void allExistingDocumentsRetainCanonicalJsonHashAndActiveSchema() throws Exception {
        var snapshot = catalog(all(),BuiltInStudies.contribution()).snapshot();
        assertEquals(16,snapshot.studies().size()); assertEquals(40,snapshot.studies().stream().mapToInt(s -> s.presets().size()).sum());
        var documents = new ScenarioDocuments(65536);
        for (var study : snapshot.studies()) {
            assertTrue(Files.isRegularFile(Path.of(study.caseStudy().sourcePath())));
            for (var preset : study.presets()) {
                try (var original = Files.newInputStream(Path.of(preset.sourcePath()))) {
                    var definition = documents.read(original,ScenarioDocuments.format(preset.sourcePath()));
                    assertEquals(documents.canonical(definition),preset.definitionJson());
                    assertEquals(documents.hash(definition),preset.definitionSha256());
                    if (definition.input()!=null) assertEquals(definition.input().sha256(),ScenarioDocuments.sha256(Files.readAllBytes(Path.of(preset.sourcePath()).getParent().resolve(definition.input().file()))));
                }
            }
        }
        assertEquals(18,snapshot.patterns().size());
        assertThrows(UnsupportedOperationException.class,() -> snapshot.studies().clear());
        assertThrows(UnsupportedOperationException.class,() -> snapshot.studies().getFirst().presets().clear());
    }
    @Test void exposesOnlyActiveStudiesAndSeparateReadOnlyHttpContract() throws Exception {
        var c = catalog(ExperimentCatalog.load(),BuiltInStudies.contribution());
        assertEquals(3,c.snapshot().studies().size());
        var mvc = MockMvcBuilders.standaloneSetup(new StudyCatalogController(c)).build();
        mvc.perform(get("/dashboard/v1/study-catalog")).andExpect(status().isOk()).andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.studies.length()").value(3)).andExpect(jsonPath("$.studies[0].presets[0].definitionSha256").isString());
        mvc.perform(post("/dashboard/v1/study-catalog")).andExpect(status().isMethodNotAllowed());
        assertTrue(catalog(ExperimentCatalog.load()).snapshot().studies().isEmpty());
    }
    @Test void validatesDuplicateReferencesResourcesAndBoundsIncludingInactiveStudies() {
        var builtin=BuiltInStudies.contribution(); var study=builtin.studies().getFirst();
        assertThrows(IllegalArgumentException.class,() -> catalog(all(),builtin,builtin));
        var bad = new Study(study.experimentId(),study.caseStudy(),study.strategies(),List.of(new Preset("missing",List.of("absent"),study.presets().getFirst().sourcePath())));
        assertThrows(IllegalArgumentException.class,() -> catalog(ExperimentCatalog.load(),new StudyContribution(List.of(bad),List.of())));
        var wrong = new Study("inactive.other",study.caseStudy(),study.strategies(),study.presets());
        assertThrows(IllegalArgumentException.class,() -> catalog(ExperimentCatalog.load(),new StudyContribution(List.of(wrong),List.of())));
        var missing = new Study(study.experimentId(),study.caseStudy(),study.strategies(),List.of(new Preset("missing",List.of(study.strategies().getFirst().id()),"experiments/missing.json")));
        assertThrows(IllegalArgumentException.class,() -> catalog(all(),new StudyContribution(List.of(missing),List.of())));
        assertThrows(IllegalArgumentException.class,() -> new Preset("x",List.of("a"),"experiments/../secret.json"));
        assertThrows(IllegalArgumentException.class,() -> new StudyContribution(Collections.nCopies(257,study),List.of()));
        var duplicateStrategy = new Study(study.experimentId(),study.caseStudy(),Collections.nCopies(2,study.strategies().getFirst()),List.of());
        assertThrows(IllegalArgumentException.class,() -> catalog(all(),new StudyContribution(List.of(duplicateStrategy),List.of())));
    }
    @Test void springDiscoversIndependentStudyContributions() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            var active = new ExperimentCatalog(List.of(new ExperimentModule() {
                public ModuleDescriptor module() { return new ModuleDescriptor("custom", "Custom", "examples"); }
                public List<ExperimentDescriptor> experiments() { return List.of(new ExperimentDescriptor("custom.demo", "Demo", "CUSTOM", "NONE", Map.of(), List.of("SINGLE_PASS"))); }
            }));
            context.registerBean(ExperimentCatalog.class, () -> active);
            context.registerBean("customStudy", StudyContribution.class, () -> new StudyContribution(List.of(new Study("custom.demo",
                    new CaseStudy("Demo", "docs/scenarios.md", "NEW-DESIGN", "Synthetic"), List.of(new Strategy("baseline", "Baseline", "Synthetic strategy")), List.of())), List.of()));
            context.register(PatternConfiguration.class); context.refresh();
            assertEquals("custom.demo", context.getBean(StudyCatalog.class).snapshot().studies().getFirst().experimentId());
        }
    }
    @Test void semanticDeprecationIsAdvisoryWithNoAliasesOrCycles() {
        var policy = new PatternPolicy("bounded-queue","1.1.0","DEPRECATED","bounded-parallelism");
        var c = catalog(ExperimentCatalog.load(),new StudyContribution(List.of(),List.of(policy)));
        assertTrue(c.snapshot().patterns().contains(policy));
        assertThrows(IllegalArgumentException.class,() -> catalog(all(),new StudyContribution(List.of(),List.of(policy,
                new PatternPolicy("bounded-parallelism","1.0.0","DEPRECATED","bounded-queue")))));
        assertThrows(IllegalArgumentException.class,() -> catalog(all(),new StudyContribution(List.of(),List.of(new PatternPolicy("bounded-queue","1.0.0","DEPRECATED","missing")))));
        assertThrows(IllegalArgumentException.class,() -> new PatternPolicy("x","latest","ACTIVE",null));
        assertThrows(IllegalArgumentException.class,() -> new PatternPolicy("x","1.0.0","ACTIVE","y"));
    }
}
