package com.pnomeer.lab.scenario;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

public final class ScenarioDocuments {
    public enum Format { JSON, YAML }
    private final int limit;
    private final JsonMapper json;

    public ScenarioDocuments(int limit) {
        this.limit = limit;
        var factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(limit).build()).build();
        json = JsonMapper.builder(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }

    public ScenarioDefinition read(InputStream stream, Format format) throws IOException {
        byte[] bytes = readBounded(stream, limit);
        Object root;
        try {
            if (format == Format.JSON) root = json.readValue(bytes, Map.class);
            else {
                var options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                options.setMaxAliasesForCollections(0);
                options.setNestingDepthLimit(20);
                options.setCodePointLimit(limit);
                root = new Yaml(new SafeConstructor(options)).load(new ByteArrayInputStream(bytes));
            }
        } catch (RuntimeException ex) { throw new ScenarioException("Invalid " + format + " scenario document"); }
        var document = object(root, "document");
        keys(document, Set.of("schemaVersion", "experiment", "scenario", "parameters", "measurement", "input"));
        if (!isInteger(document.get("schemaVersion")) || ((Number)document.get("schemaVersion")).longValue() != 1) {
            throw new ScenarioException("schemaVersion must be 1");
        }
        String experiment = text(document.get("experiment"), "experiment", 200);
        var scenario = object(document.get("scenario"), "scenario");
        keys(scenario, Set.of("id", "hypothesis"));
        String id = text(scenario.get("id"), "scenario.id", 200);
        if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) throw new ScenarioException("Invalid scenario.id");
        String hypothesis = text(scenario.get("hypothesis"), "scenario.hypothesis", 4000);
        var measurement = object(document.get("measurement"), "measurement");
        keys(measurement, Set.of("mode"));
        if (!"SINGLE_PASS".equals(measurement.get("mode"))) throw new ScenarioException("Only measurement.mode=SINGLE_PASS is supported");
        var parameters = object(document.get("parameters"), "parameters");
        if (parameters.size() > 32) throw new ScenarioException("Too many parameters");
        for (var entry : parameters.entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof String || value instanceof Boolean || isInteger(value))) {
                throw new ScenarioException("Parameter must be string, boolean or integer: " + entry.getKey());
            }
        }
        ScenarioDefinition.Input input = null;
        if (document.containsKey("input")) {
            var data = object(document.get("input"), "input");
            keys(data, Set.of("file", "sha256"));
            String file = text(data.get("file"), "input.file", 512);
            Path path = Path.of(file);
            if (path.isAbsolute() || file.contains("\\") || file.contains(":") || file.contains("\r") || file.contains("\n")) {
                throw new ScenarioException("input.file must be a relative path inside the scenario directory");
            }
            for (Path part : path) if (part.toString().equals("..")) throw new ScenarioException("input.file cannot traverse parent directories");
            if (!file.endsWith(".xlsx")) throw new ScenarioException("input.file must end with .xlsx");
            String hash = text(data.get("sha256"), "input.sha256", 64);
            if (!hash.matches("[a-f0-9]{64}")) throw new ScenarioException("input.sha256 must be lowercase SHA-256");
            input = new ScenarioDefinition.Input(file, hash);
        }
        return new ScenarioDefinition(1, experiment, new ScenarioDefinition.Scenario(id, hypothesis), parameters,
                new ScenarioDefinition.Measurement("SINGLE_PASS"), input);
    }

    public String canonical(ScenarioDefinition definition) {
        var root = new TreeMap<String, Object>();
        root.put("schemaVersion", 1); root.put("experiment", definition.experiment());
        root.put("scenario", new TreeMap<>(Map.of("id", definition.scenario().id(), "hypothesis", definition.scenario().hypothesis())));
        root.put("parameters", new TreeMap<>(definition.parameters()));
        root.put("measurement", Map.of("mode", definition.measurement().mode()));
        if (definition.input() != null) root.put("input", new TreeMap<>(Map.of("file", definition.input().file(), "sha256", definition.input().sha256())));
        return json.writeValueAsString(root);
    }
    public String hash(ScenarioDefinition definition) { return sha256(canonical(definition).getBytes(StandardCharsets.UTF_8)); }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static byte[] readBounded(InputStream stream, int limit) throws IOException {
        byte[] bytes = stream.readNBytes(limit + 1);
        if (bytes.length > limit) throw new ScenarioException("Input exceeds configured byte limit: " + limit);
        return bytes;
    }
    public static Format format(String name) {
        if (name.endsWith(".yaml") || name.endsWith(".yml")) return Format.YAML;
        if (name.endsWith(".json")) return Format.JSON;
        throw new ScenarioException("Scenario must be .json, .yaml or .yml");
    }
    public static boolean isInteger(Object value) { return value instanceof Integer || value instanceof Long; }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new ScenarioException(field + " must be an object with string keys");
        }
        return (Map<String, Object>) map;
    }
    private static void keys(Map<String, Object> map, Set<String> allowed) {
        for (String key : map.keySet()) if (!allowed.contains(key)) throw new ScenarioException("Unsupported field: " + key);
    }
    private static String text(Object value, String field, int limit) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > limit) throw new ScenarioException("Invalid " + field);
        return text;
    }
}
