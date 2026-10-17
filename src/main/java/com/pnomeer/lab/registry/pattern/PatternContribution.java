package com.pnomeer.lab.registry.pattern;

import java.util.HashSet;
import java.util.List;

/** Trusted, bounded build-time metadata. Never supplies execution commands or Run data. */
public record PatternContribution(List<Category> categories, List<Pattern> patterns, List<Binding> bindings) {
    public static final int MAX_CATEGORIES = 128, MAX_PATTERNS = 1024, MAX_BINDINGS = 4096;

    public PatternContribution {
        categories = copy(categories, MAX_CATEGORIES);
        patterns = copy(patterns, MAX_PATTERNS);
        bindings = copy(bindings, MAX_BINDINGS);
    }
    public record Category(String id, String title) {
        public Category { identifier(id); text(title, 200); }
    }
    public record Pattern(String id, String categoryId, String title, String problem,
                          String verification, String limitations) {
        public Pattern {
            identifier(id); identifier(categoryId); text(title, 200);
            text(problem, 1000); text(verification, 1000); text(limitations, 1000);
        }
    }
    public record Classification(String categoryId, List<String> patternIds) {
        public Classification {
            identifier(categoryId);
            patternIds = copy(patternIds, 32);
            if (patternIds.isEmpty()) throw new IllegalArgumentException("Classification needs a pattern");
            patternIds.forEach(PatternContribution::identifier);
            if (new HashSet<>(patternIds).size() != patternIds.size()) {
                throw new IllegalArgumentException("Duplicate pattern reference");
            }
            patternIds = patternIds.stream().sorted().toList();
        }
    }
    public record Binding(String experimentId, Classification classification) {
        public Binding {
            identifier(experimentId);
            if (classification == null) throw new IllegalArgumentException("Missing classification");
        }
    }
    private static void identifier(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9.-]{0,99}")) {
            throw new IllegalArgumentException("Invalid pattern metadata ID");
        }
    }
    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid pattern metadata text");
        }
    }
    private static <T> List<T> copy(List<T> values, int max) {
        if (values == null || values.size() > max || values.stream().anyMatch(v -> v == null)) {
            throw new IllegalArgumentException("Invalid pattern metadata list");
        }
        return List.copyOf(values);
    }
}
