package com.pnomeer.lab.registry.pattern;

import java.util.*;

/** Trusted explanatory metadata, separate from executable scenario documents. IDs are scoped to a study. */
public record StudyContribution(List<Study> studies, List<PatternPolicy> policies) {
    public StudyContribution { studies = bounded(studies, 256); policies = bounded(policies, 1024); }
    public record CaseStudy(String title, String sourcePath, String provenance, String limitations) {
        public CaseStudy { text(title); path(sourcePath); text(provenance); text(limitations); }
    }
    public record Strategy(String id, String title, String implementation) {
        public Strategy { identifier(id); text(title); text(implementation); }
    }
    public record Preset(String id, List<String> strategyIds, String sourcePath) {
        public Preset { identifier(id); strategyIds = bounded(strategyIds, 32); strategyIds.forEach(StudyContribution::identifier);
            if (strategyIds.isEmpty() || new HashSet<>(strategyIds).size() != strategyIds.size()) throw new IllegalArgumentException("Invalid strategy references");
            path(sourcePath); if (!sourcePath.startsWith("experiments/") || !(sourcePath.endsWith(".json") || sourcePath.endsWith(".yaml"))) throw new IllegalArgumentException("Invalid preset path"); }
    }
    public record Study(String experimentId, CaseStudy caseStudy, List<Strategy> strategies, List<Preset> presets) {
        public Study { identifier(experimentId); Objects.requireNonNull(caseStudy); strategies = bounded(strategies, 32); presets = bounded(presets, 64);
            if (strategies.isEmpty()) throw new IllegalArgumentException("Study needs strategies"); }
    }
    public record PatternPolicy(String patternId, String semanticVersion, String status, String replacementPatternId) {
        public PatternPolicy { identifier(patternId);
            if (semanticVersion == null || !semanticVersion.matches("[1-9][0-9]{0,3}\\.[0-9]{1,4}\\.[0-9]{1,4}")) throw new IllegalArgumentException("Invalid semantic version");
            if (status == null || !Set.of("ACTIVE", "DEPRECATED").contains(status)) throw new IllegalArgumentException("Invalid pattern status");
            if (replacementPatternId != null) { identifier(replacementPatternId); if (!status.equals("DEPRECATED")) throw new IllegalArgumentException("Only deprecated patterns have replacements"); }
        }
    }
    private static void identifier(String s) { if (s == null || !s.matches("[a-z][a-z0-9.-]{0,99}")) throw new IllegalArgumentException("Invalid study ID"); }
    private static void text(String s) { if (s == null || s.isBlank() || s.length() > 2000 || s.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid study text"); }
    private static void path(String s) { text(s); if (s.length() > 300 || !s.matches("[a-zA-Z0-9._/-]+") || s.startsWith("/") || Arrays.asList(s.split("/", -1)).stream().anyMatch(p -> p.isEmpty() || p.equals("..") || p.equals("."))) throw new IllegalArgumentException("Invalid source path"); }
    private static <T> List<T> bounded(List<T> items, int max) { if (items == null || items.size() > max || items.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Invalid study list"); return List.copyOf(items); }
}
