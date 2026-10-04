package com.pnomeer.lab.registry;

import com.pnomeer.lab.scenario.ScenarioException;
import java.util.*;

/** Immutable metadata catalog. Runtime factories are checked against this exact snapshot. */
public final class ExperimentCatalog {
    public record ModuleEntry(ModuleDescriptor module, List<ExperimentDescriptor> experiments) {
        public ModuleEntry { experiments = List.copyOf(experiments); }
    }
    private final List<com.pnomeer.lab.metrics.MetricCatalog.Definition> metrics;
    private final List<ModuleEntry> modules;
    private final Map<String, ExperimentDescriptor> experiments;

    public ExperimentCatalog(Collection<? extends ExperimentModule> providers) {
        var metricMap = new TreeMap<String, com.pnomeer.lab.metrics.MetricCatalog.Definition>();
        var moduleMap = new TreeMap<String, ModuleEntry>();
        var experimentMap = new TreeMap<String, ExperimentDescriptor>();
        for (var provider : providers) {
            var module = Objects.requireNonNull(provider.module(), "Module metadata");
            var descriptors = List.copyOf(provider.experiments());
            if (descriptors.isEmpty()) throw new IllegalArgumentException("Empty module: " + module.id());
            if (moduleMap.putIfAbsent(module.id(), new ModuleEntry(module,
                    descriptors.stream().sorted(Comparator.comparing(ExperimentDescriptor::id)).toList())) != null) {
                throw new IllegalArgumentException("Duplicate module ID: " + module.id());
            }
            for (var metric : provider.metrics()) {
                if (metric == null || java.util.stream.Stream.of(metric.name(), metric.unit(), metric.scope(),
                        metric.semantics(), metric.aggregation()).anyMatch(v -> v == null || v.isBlank())) {
                    throw new IllegalArgumentException("Invalid metric definition in module: " + module.id());
                }
                if (metricMap.putIfAbsent(metric.name(), metric) != null) {
                    throw new IllegalArgumentException("Duplicate module metric: " + metric.name());
                }
            }
            for (var descriptor : descriptors) {
                if (experimentMap.putIfAbsent(descriptor.id(), descriptor) != null) {
                    throw new IllegalArgumentException("Duplicate experiment ID: " + descriptor.id());
                }
            }
        }
        if (moduleMap.isEmpty()) throw new IllegalArgumentException("No experiment modules found");
        metrics = List.copyOf(metricMap.values());
        modules = List.copyOf(moduleMap.values());
        experiments = Collections.unmodifiableMap(experimentMap);
    }

    public static ExperimentCatalog load() {
        return load(Thread.currentThread().getContextClassLoader());
    }
    public static ExperimentCatalog load(ClassLoader loader) {
        return new ExperimentCatalog(ServiceLoader.load(ExperimentModule.class, loader).stream()
                .map(ServiceLoader.Provider::get).toList());
    }
    public List<com.pnomeer.lab.metrics.MetricCatalog.Definition> metrics() { return metrics; }
    public List<ModuleEntry> modules() { return modules; }
    public List<ExperimentDescriptor> experiments() { return List.copyOf(experiments.values()); }
    public ExperimentDescriptor get(String id) {
        var descriptor = experiments.get(id);
        if (descriptor == null) throw new ScenarioException("Unknown experiment: " + id);
        return descriptor;
    }
    public void verifyRegistrations(List<ExperimentRegistration<?>> registrations) {
        var ids = new HashSet<String>();
        for (var registration : registrations) {
            String id = registration.descriptor().id();
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate runtime registration: " + id);
            if (!registration.descriptor().equals(experiments.get(id))) {
                throw new IllegalArgumentException("Runtime registration differs from module catalog: " + id);
            }
        }
        if (!ids.equals(experiments.keySet())) {
            var missing = new TreeSet<>(experiments.keySet());
            missing.removeAll(ids);
            throw new IllegalArgumentException("Missing runtime registrations: " + missing);
        }
    }
}
