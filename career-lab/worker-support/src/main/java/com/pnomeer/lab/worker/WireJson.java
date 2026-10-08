package com.pnomeer.lab.worker;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Strict JSON subset matching contracts/worker/v1; never coerces numbers or booleans. */
public final class WireJson {
    private WireJson() {}
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(4096).maxNumberLength(128).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static ObjectNode object() { return MAPPER.createObjectNode(); }
    public static ObjectNode parse(byte[] bytes) throws IOException {
        if (bytes.length > 65536) throw new ProtocolException("RECORD_LIMIT");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode value = MAPPER.readTree(text);
            if (value == null || !value.isObject()) throw new ProtocolException("OBJECT_REQUIRED");
            bounded(value, 0);
            return (ObjectNode) value;
        } catch (ProtocolException e) { throw e; }
        catch (IOException | RuntimeException e) { throw new ProtocolException("MALFORMED_JSON"); }
    }
    public static byte[] encode(ObjectNode value) throws IOException {
        bounded(value, 0);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        OutputStream boundedOutput = new OutputStream() {
            public void write(int b) throws IOException {
                if (buffer.size() == 65536) throw new ProtocolException("RECORD_LIMIT");
                buffer.write(b);
            }
            public void write(byte[] b, int offset, int length) throws IOException {
                if (length > 65536 - buffer.size()) throw new ProtocolException("RECORD_LIMIT");
                buffer.write(b, offset, length);
            }
        };
        MAPPER.writeValue(boundedOutput, value);
        return buffer.toByteArray();
    }
    private static void bounded(JsonNode value, int depth) throws ProtocolException {
        if (depth > 16 || value.size() > 64 || value.isTextual() && value.textValue().length() > 4096)
            throw new ProtocolException("VALUE_LIMIT");
        if (value.isNumber() && (!Double.isFinite(value.doubleValue()) || value.asText().length() > 128))
            throw new ProtocolException("NUMBER_LIMIT");
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (field.getKey().isEmpty() || field.getKey().length() > 100) throw new ProtocolException("KEY_LIMIT");
                bounded(field.getValue(), depth + 1);
            }
        } else if (value.isArray()) for (var item : value) bounded(item, depth + 1);
    }
    static void fields(JsonNode node, String... names) throws ProtocolException {
        if (!node.isObject() || node.size() != names.length) throw new ProtocolException("FIELDS");
        for (String name : names) if (!node.has(name)) throw new ProtocolException("FIELDS");
    }
    static String text(JsonNode n, String key, int max, boolean empty) throws ProtocolException {
        JsonNode v = n.path(key);
        if (!v.isTextual() || v.textValue().length() > max || !empty && v.textValue().isBlank())
            throw new ProtocolException("TEXT_FIELD");
        return v.textValue();
    }
    static long integer(JsonNode n, String key, long min, long max) throws ProtocolException {
        JsonNode v = n.path(key);
        if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < min || v.longValue() > max)
            throw new ProtocolException("INTEGER_FIELD");
        return v.longValue();
    }
    static void id(JsonNode n, String key) throws ProtocolException {
        if (!text(n, key, 100, false).matches("[a-zA-Z0-9._-]{1,100}")) throw new ProtocolException("ID_FIELD");
    }
    static void hash(JsonNode n, String key) throws ProtocolException {
        if (!text(n, key, 64, false).matches("[a-f0-9]{64}")) throw new ProtocolException("HASH_FIELD");
    }
    static void choice(JsonNode n, String key, String... values) throws ProtocolException {
        if (!Set.of(values).contains(text(n, key, 100, false))) throw new ProtocolException("ENUM_FIELD");
    }
    static void scalars(JsonNode n) throws ProtocolException {
        if (!n.isObject() || n.size() > 64) throw new ProtocolException("MAP_FIELD");
        for (JsonNode v : n) if (!v.isTextual() && !v.isBoolean() && !v.isNumber()) throw new ProtocolException("SCALAR_FIELD");
    }
    static void command(ObjectNode n) throws ProtocolException {
        fields(n, "protocolVersion", "runId", "experimentId", "parameters", "definitionSha256", "deadlineEpochMs", "seed", "workspace");
        integer(n, "protocolVersion", 1, 1); id(n, "runId"); id(n, "experimentId");
        scalars(n.path("parameters")); hash(n, "definitionSha256");
        integer(n, "deadlineEpochMs", 1, Long.MAX_VALUE); integer(n, "seed", Long.MIN_VALUE, Long.MAX_VALUE);
        String path = text(n, "workspace", 4096, false);
        // OS-specific absolute path, directory ownership and symlink checks belong to process launch.
        if (path.indexOf('\0') >= 0) throw new ProtocolException("WORKSPACE_FIELD");
    }
    static void payload(String type, ObjectNode p) throws ProtocolException {
        switch (type) {
            case "HELLO" -> {
                fields(p, "moduleId", "experimentId", "definitionSha256", "artifactSha256", "environment", "git");
                id(p, "moduleId"); id(p, "experimentId"); hash(p, "definitionSha256"); hash(p, "artifactSha256");
                JsonNode env = p.path("environment");
                fields(env, "javaVersion", "javaVendor", "vmName", "osName", "osVersion", "osArch", "availableProcessors", "maxHeapBytes");
                for (String k : List.of("javaVersion", "javaVendor", "vmName", "osName", "osVersion", "osArch")) text(env, k, 256, false);
                integer(env, "availableProcessors", 1, Integer.MAX_VALUE); integer(env, "maxHeapBytes", 1, Long.MAX_VALUE);
                JsonNode git = p.path("git"); fields(git, "commit", "dirty", "error");
                if (!git.path("commit").isNull()) text(git, "commit", 128, false);
                if (!git.path("error").isNull()) text(git, "error", 1024, false);
                if (!git.path("dirty").isNull() && !git.path("dirty").isBoolean()) throw new ProtocolException("GIT_FIELD");
            }
            case "PHASE" -> { fields(p, "phase"); choice(p, "phase", "PREPARING", "MEASURING", "FINALIZING"); }
            case "METRIC" -> {
                fields(p, "timestampEpochMs", "gauges", "counters", "labels"); integer(p, "timestampEpochMs", 0, Long.MAX_VALUE);
                for (String key : List.of("gauges", "counters", "labels")) {
                    JsonNode map = p.path(key);
                    if (!map.isObject()) throw new ProtocolException("METRIC_MAP");
                    var names = map.fieldNames();
                    while (names.hasNext()) {
                        String name = names.next();
                        if (!name.matches("[a-zA-Z0-9._-]{1,100}")) throw new ProtocolException("METRIC_KEY");
                        JsonNode v = map.path(name);
                        if (key.equals("labels")) text(map, name, 256, true);
                        else if (key.equals("counters")) integer(map, name, Long.MIN_VALUE, Long.MAX_VALUE);
                        else if (!v.isNumber() || !Double.isFinite(v.doubleValue())) throw new ProtocolException("GAUGE_FIELD");
                    }
                }
            }
            case "CHECK" -> {
                fields(p, "checkId", "status", "expected", "observed", "description"); id(p, "checkId"); choice(p, "status", "PASS", "FAIL");
                for (String k : List.of("expected", "observed", "description")) text(p, k, 4096, true);
            }
            case "ARTIFACT" -> {
                fields(p, "path", "sizeBytes", "sha256", "kind");
                String path = text(p, "path", 1024, false);
                if (path.startsWith("/") || path.contains("\\") || path.contains(":") || path.indexOf('\0') >= 0
                        || Arrays.stream(path.split("/", -1)).anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")))
                    throw new ProtocolException("ARTIFACT_PATH");
                integer(p, "sizeBytes", 0, Long.MAX_VALUE); hash(p, "sha256"); choice(p, "kind", "JFR", "LOG", "REPORT");
            }
            case "RESULT" -> {
                fields(p, "status", "reason", "summary", "lastSequence", "counts");
                choice(p, "status", "SUCCEEDED", "FAILED", "INTERRUPTED"); text(p, "reason", 1024, true); scalars(p.path("summary"));
                integer(p, "lastSequence", 1, 99999);
                fields(p.path("counts"), "HELLO", "PHASE", "METRIC", "CHECK", "ARTIFACT");
                for (String k : List.of("HELLO", "PHASE", "METRIC", "CHECK", "ARTIFACT")) integer(p.path("counts"), k, 0, 99999);
            }
            default -> throw new ProtocolException("EVENT_TYPE");
        }
    }
}
