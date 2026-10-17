package com.pnomeer.lab.registry.pattern;

import com.pnomeer.lab.registry.ExperimentCatalog;
import java.util.*;
import static com.pnomeer.lab.registry.pattern.PatternContribution.*;

/** Current classification only: does not rewrite historical Run metadata or imply readiness. */
public final class PatternCatalog {
    public record ExperimentView(String experimentId, String sourceModuleId, String sourceCollection,
                                 Classification classification) {}
    public record Snapshot(int schemaVersion, List<Category> categories, List<Pattern> patterns,
                           List<ExperimentView> experiments) {
        public Snapshot {
            categories = List.copyOf(categories); patterns = List.copyOf(patterns);
            experiments = List.copyOf(experiments);
        }
    }
    private final Snapshot snapshot;

    public PatternCatalog(ExperimentCatalog active, List<PatternContribution> contributions) {
        if (contributions == null || contributions.size() > 256) {
            throw new IllegalArgumentException("Too many pattern contributions");
        }
        var categories = new TreeMap<String, Category>();
        var patterns = new TreeMap<String, Pattern>();
        var bindings = new TreeMap<String, Classification>();
        for (var contribution : contributions) {
            Objects.requireNonNull(contribution, "Pattern contribution");
            for (var c : contribution.categories()) add(categories, c.id(), c, MAX_CATEGORIES);
            for (var p : contribution.patterns()) add(patterns, p.id(), p, MAX_PATTERNS);
            for (var b : contribution.bindings()) add(bindings, b.experimentId(), b.classification(), MAX_BINDINGS);
        }
        for (var pattern : patterns.values()) {
            if (!categories.containsKey(pattern.categoryId())) {
                throw new IllegalArgumentException("Unknown category for pattern: " + pattern.id());
            }
        }
        // Validate inactive bindings too; enabling a worker must not reveal invalid metadata later.
        for (var entry : bindings.entrySet()) {
            var classification = entry.getValue();
            if (!categories.containsKey(classification.categoryId())
                    || !patterns.keySet().containsAll(classification.patternIds())) {
                throw new IllegalArgumentException("Unknown classification reference: " + entry.getKey());
            }
            if (classification.patternIds().stream().noneMatch(id ->
                    patterns.get(id).categoryId().equals(classification.categoryId()))) {
                throw new IllegalArgumentException("Primary category has no linked pattern: " + entry.getKey());
            }
        }
        var experiments = new ArrayList<ExperimentView>();
        for (var module : active.modules()) {
            for (var experiment : module.experiments()) {
                if (experiments.size() >= MAX_BINDINGS) throw new IllegalArgumentException("Too many active experiments");
                experiments.add(new ExperimentView(experiment.id(), module.module().id(),
                        module.module().collection(), bindings.get(experiment.id())));
            }
        }
        experiments.sort(Comparator.comparing(ExperimentView::experimentId));
        snapshot = new Snapshot(1, List.copyOf(categories.values()), List.copyOf(patterns.values()), experiments);
    }
    private static <T> void add(Map<String, T> values, String id, T value, int max) {
        if (values.containsKey(id)) throw new IllegalArgumentException("Duplicate pattern metadata ID: " + id);
        if (values.size() >= max) throw new IllegalArgumentException("Pattern metadata count exceeds " + max);
        values.put(id, value);
    }
    public Snapshot snapshot() { return snapshot; }
}
