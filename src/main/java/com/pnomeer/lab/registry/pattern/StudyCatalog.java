package com.pnomeer.lab.registry.pattern;

import com.pnomeer.lab.registry.ExperimentCatalog;
import com.pnomeer.lab.scenario.*;
import java.io.*;
import java.util.*;
import static com.pnomeer.lab.registry.pattern.StudyContribution.*;

/** Startup snapshot; neither reads nor rewrites stored Runs. No resource path comes from HTTP input. */
public final class StudyCatalog {
    public record PresetView(String id, List<String> strategyIds, String sourcePath, String definitionJson, String definitionSha256) {}
    public record StudyView(String experimentId, CaseStudy caseStudy, List<Strategy> strategies, List<PresetView> presets) {}
    public record Snapshot(int schemaVersion, List<PatternPolicy> patterns, List<StudyView> studies) {}
    private final Snapshot snapshot;
    public StudyCatalog(ExperimentCatalog active, PatternCatalog patterns, List<StudyContribution> contributions) {
        this(active, patterns, contributions, Thread.currentThread().getContextClassLoader());
    }
    StudyCatalog(ExperimentCatalog active, PatternCatalog patterns, List<StudyContribution> contributions, ClassLoader resources) {
        if (contributions.size() > 256) throw new IllegalArgumentException("Too many study contributions");
        var studies = new TreeMap<String, Study>(); var policies = new TreeMap<String, PatternPolicy>();
        for (var c : contributions) {
            for (var s : c.studies()) if (studies.putIfAbsent(s.experimentId(), s) != null || studies.size() > 256) throw new IllegalArgumentException("Duplicate or excessive studies");
            for (var p : c.policies()) if (policies.putIfAbsent(p.patternId(), p) != null || policies.size() > 1024) throw new IllegalArgumentException("Duplicate or excessive policies");
        }
        var patternIds = new HashSet<String>(); patterns.snapshot().patterns().forEach(p -> patternIds.add(p.id()));
        if (!patternIds.containsAll(policies.keySet())) throw new IllegalArgumentException("Unknown pattern policy");
        patternIds.forEach(id -> policies.putIfAbsent(id, new PatternPolicy(id, "1.0.0", "ACTIVE", null)));
        for (var policy : policies.values()) {
            var seen = new HashSet<String>(); var cursor = policy;
            while (cursor.replacementPatternId() != null) {
                if (!seen.add(cursor.patternId()) || !policies.containsKey(cursor.replacementPatternId())) throw new IllegalArgumentException("Cyclic or missing pattern replacement");
                cursor = policies.get(cursor.replacementPatternId());
            }
        }
        var descriptors = new HashMap<String, com.pnomeer.lab.registry.ExperimentDescriptor>();
        active.experiments().forEach(d -> descriptors.put(d.id(), d));
        var documents = new ScenarioDocuments(65536); var views = new ArrayList<StudyView>();
        long totalBytes = 0;
        for (var study : studies.values()) {
            var strategyIds = new HashSet<String>();
            for (var strategy : study.strategies()) if (!strategyIds.add(strategy.id())) throw new IllegalArgumentException("Duplicate strategy ID");
            var presetIds = new HashSet<String>(); var presets = new ArrayList<PresetView>();
            for (var preset : study.presets()) {
                if (!presetIds.add(preset.id()) || !strategyIds.containsAll(preset.strategyIds())) throw new IllegalArgumentException("Invalid preset strategy references");
                try (var stream = resources.getResourceAsStream(preset.sourcePath())) {
                    if (stream == null) throw new IllegalArgumentException("Missing preset resource: " + preset.sourcePath());
                    byte[] bytes = ScenarioDocuments.readBounded(stream, 65536);
                    totalBytes += bytes.length;
                    if (totalBytes > 4 * 1024 * 1024) throw new IllegalArgumentException("Preset catalog exceeds 4 MiB");
                    var definition = documents.read(new ByteArrayInputStream(bytes), ScenarioDocuments.format(preset.sourcePath()));
                    if (!definition.experiment().equals(study.experimentId())) throw new IllegalArgumentException("Preset experiment mismatch");
                    if (descriptors.containsKey(study.experimentId())) descriptors.get(study.experimentId()).validate(definition);
                    presets.add(new PresetView(preset.id(), preset.strategyIds(), preset.sourcePath(), documents.canonical(definition), documents.hash(definition)));
                } catch (IOException e) { throw new IllegalArgumentException("Cannot read preset resource", e); }
            }
            if (descriptors.containsKey(study.experimentId())) views.add(new StudyView(study.experimentId(), study.caseStudy(),
                    study.strategies().stream().sorted(Comparator.comparing(Strategy::id)).toList(), presets.stream().sorted(Comparator.comparing(PresetView::id)).toList()));
        }
        snapshot = new Snapshot(1, List.copyOf(policies.values()), List.copyOf(views));
    }
    public Snapshot snapshot() { return snapshot; }
}
