package com.pnomeer.lab.registry.measurement;

import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** A bounded declaration captured at admission, never reconstructed for historical runs. */
public record MeasurementContract(int schemaVersion, String experimentId, String methodVersion,
        List<Property> properties, List<Measure> measures, List<String> requiredParameters, String limitations) {
    public static final String PARAMETER = "measurement.contract.v1";
    public static final int MAX_BYTES = 16384;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public enum Kind { counter, gauge }
    public record Property(String id, String meaning, String evidence) {
        public Property { id = identifier(id); meaning = text(meaning); evidence = text(evidence); }
    }
    public record Measure(String role, Kind kind, String name, String unit, String scope, String semantics, String window) {
        public Measure {
            role = identifier(role); Objects.requireNonNull(kind); name = identifier(name);
            unit = text(unit); scope = text(scope); semantics = text(semantics); window = text(window);
        }
    }
    public MeasurementContract {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported measurement contract");
        experimentId = identifier(experimentId); methodVersion = text(methodVersion); limitations = text(limitations);
        properties = bounded(properties, 16); measures = bounded(measures, 32); requiredParameters = bounded(requiredParameters, 64);
        unique(properties.stream().map(Property::id).toList()); unique(measures.stream().map(Measure::role).toList());
        requiredParameters.forEach(MeasurementContract::identifier); unique(requiredParameters);
    }
    public String encode() {
        String value = JSON.writeValueAsString(this);
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Measurement contract exceeds 16 KiB");
        return value;
    }
    /** Missing, malformed and future contracts are unknown, never guessed from today's catalog. */
    public static Optional<MeasurementContract> read(String value, String experimentId) {
        if (value == null || value.length() > MAX_BYTES || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return Optional.empty();
        try {
            var contract = JSON.readValue(value, MeasurementContract.class);
            return contract != null && contract.experimentId().equals(experimentId) ? Optional.of(contract) : Optional.empty();
        } catch (RuntimeException ex) { return Optional.empty(); }
    }
    private static String identifier(String s) {
        if (s == null || !s.matches("[a-zA-Z][a-zA-Z0-9.-]{0,99}")) throw new IllegalArgumentException("Invalid measurement identifier");
        return s;
    }
    private static String text(String s) {
        if (s == null || s.isBlank() || s.length() > 1000) throw new IllegalArgumentException("Invalid measurement text");
        return s;
    }
    private static <T> List<T> bounded(List<T> list, int limit) {
        if (list == null || list.size() > limit) throw new IllegalArgumentException("Measurement list exceeds limit");
        return List.copyOf(list);
    }
    private static void unique(List<String> keys) {
        if (new HashSet<>(keys).size() != keys.size()) throw new IllegalArgumentException("Duplicate measurement key");
    }
}
