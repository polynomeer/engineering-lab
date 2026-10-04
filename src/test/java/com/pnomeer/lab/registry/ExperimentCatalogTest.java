package com.pnomeer.lab.registry;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.metrics.MetricCatalog;
import com.pnomeer.lab.scenario.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

public class ExperimentCatalogTest {
    @TempDir Path temporary;
    private static final ExperimentDescriptor SAMPLE = new ExperimentDescriptor("sample.count", "Sample", "SAMPLE",
            "NONE", Map.of(), List.of("SINGLE_PASS"));
    public static class SampleModule implements ExperimentModule {
        public ModuleDescriptor module() { return new ModuleDescriptor("sample", "Sample module", "career"); }
        public List<ExperimentDescriptor> experiments() { return List.of(SAMPLE); }
        public List<MetricCatalog.Definition> metrics() {
            return List.of(new MetricCatalog.Definition("sample.items", "items", "run", "RUN_CUMULATIVE", "Use final value"));
        }
    }
    private static ExperimentRegistration<ExperimentScenario> registration(ExperimentDescriptor descriptor) {
        return new ExperimentRegistration<>(descriptor, d -> new Experiment<>() {
            public String type() { return descriptor.id(); }
            public ExperimentSummary execute(ExperimentScenario scenario, Consumer<MetricPoint> sink) {
                return new ExperimentSummary(Map.of("sample.items", 2L), Map.of(), Map.of());
            }
        }, (d, input) -> () -> d.scenario().id());
    }
    @Test void discoversAdditionalClasspathModuleWithoutEditingCentralLists() throws Exception {
        Path service = temporary.resolve("META-INF/services/" + ExperimentModule.class.getName());
        Files.createDirectories(service.getParent());
        Files.writeString(service, SampleModule.class.getName());
        try (var loader = new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL()}, getClass().getClassLoader())) {
            var catalog = ExperimentCatalog.load(loader);
            assertEquals(4, catalog.modules().size());
            assertEquals(SAMPLE, catalog.get("sample.count"));
            assertEquals("items", catalog.metrics().getFirst().unit());
            assertEquals(3, ExperimentCatalog.load().modules().size());
        }
    }
    public static class MetricProbe {
        public static void main(String[] args) {
            if (!"items".equals(MetricCatalog.unit("sample.items"))
                    || !"rows".equals(MetricCatalog.unit("rows.inserted"))) {
                throw new AssertionError("Module metrics and original metrics must coexist");
            }
        }
    }
    @Test void moduleMetricIsAvailableToTheSharedMetricCatalog() throws Exception {
        Path service = temporary.resolve("META-INF/services/" + ExperimentModule.class.getName());
        Files.createDirectories(service.getParent());
        Files.writeString(service, SampleModule.class.getName());
        Path output = temporary.resolve("metric-probe.log");
        String classpath = temporary + java.io.File.pathSeparator + System.getProperty("lab.benchmark.classpath");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, MetricProbe.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "Metric probe timed out");
            assertEquals(0, child.exitValue(), () -> {
                try { return Files.readString(output); } catch (java.io.IOException ex) { return ex.toString(); }
            });
        } finally { child.destroyForcibly(); }
    }
    @Test void newModuleBindsValidatesAndExecutesThroughExistingRegistry() throws Exception {
        var catalog = new ExperimentCatalog(List.of(new SampleModule()));
        var registration = registration(SAMPLE);
        catalog.verifyRegistrations(List.of(registration));
        var registry = new ExperimentRegistry(List.of(registration));
        var definition = new ScenarioDefinition(1, "sample.count", new ScenarioDefinition.Scenario("demo", "Count items"),
                Map.of(), new ScenarioDefinition.Measurement("SINGLE_PASS"), null);
        registry.validate(definition);
        var invocation = registry.bind(definition, null, new ScenarioDocuments(65536));
        assertEquals(2L, execute(invocation).counters().get("sample.items"));
        assertThrows(ScenarioException.class, () -> registry.get("absent"));
    }
    private static <C extends ExperimentScenario> ExperimentSummary execute(ExperimentInvocation<C> invocation) throws Exception {
        return invocation.experiment().execute(invocation.scenario(), point -> { });
    }
    @Test void failsOnDuplicateModuleExperimentAndMetricOwnership() {
        var sample = new SampleModule();
        assertThrows(IllegalArgumentException.class, () -> new ExperimentCatalog(List.of(sample, sample)));
        ExperimentModule sameExperiment = new ExperimentModule() {
            public ModuleDescriptor module() { return new ModuleDescriptor("other", "Other", "engineering"); }
            public List<ExperimentDescriptor> experiments() { return List.of(SAMPLE); }
        };
        assertThrows(IllegalArgumentException.class, () -> new ExperimentCatalog(List.of(sample, sameExperiment)));
        ExperimentModule sameMetric = new SampleModule() {
            public ModuleDescriptor module() { return new ModuleDescriptor("other", "Other", "engineering"); }
            public List<ExperimentDescriptor> experiments() {
                return List.of(new ExperimentDescriptor("other.count", "Other", "SAMPLE", "NONE", Map.of(), List.of("SINGLE_PASS")));
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new ExperimentCatalog(List.of(sample, sameMetric)));
    }
    @Test void failsAtStartupWhenSchemaAndRuntimeBindingsDrift() {
        var catalog = new ExperimentCatalog(List.of(new SampleModule()));
        assertThrows(IllegalArgumentException.class, () -> catalog.verifyRegistrations(List.of()));
        var binding = registration(SAMPLE);
        assertThrows(IllegalArgumentException.class, () -> catalog.verifyRegistrations(List.of(binding, binding)));
        var changed = new ExperimentDescriptor("sample.count", "Changed schema", "SAMPLE", "NONE", Map.of(), List.of("SINGLE_PASS"));
        assertThrows(IllegalArgumentException.class, () -> catalog.verifyRegistrations(List.of(registration(changed))));
    }
    @Test void snapshotsCannotBeMutatedByConsumers() {
        var catalog = new ExperimentCatalog(List.of(new SampleModule()));
        assertThrows(UnsupportedOperationException.class, () -> catalog.modules().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.modules().getFirst().experiments().clear());
        assertThrows(UnsupportedOperationException.class, () -> catalog.metrics().clear());
    }
}
