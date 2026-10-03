package com.pnomeer.lab.scenario;

import com.pnomeer.lab.registry.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioDocumentsTest {
    private final ScenarioDocuments documents = new ScenarioDocuments(65536);
    private static final String YAML = """
            schemaVersion: 1
            experiment: concurrency.queue-contention
            scenario: {id: small-buffer, hypothesis: A slow consumer causes producer waiting}
            parameters: {queueCapacity: 2, producerThreads: 1, consumerThreads: 1, itemsPerProducer: 10, consumerDelayMs: 1}
            measurement: {mode: SINGLE_PASS}
            """;
    private ScenarioDefinition parse(String text) throws Exception {
        return documents.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), ScenarioDocuments.Format.YAML);
    }
    @Test void optionalFlushIntervalPreservesOldDefinitionsAndValidatesExplicitValues() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("experiments/pipeline/excel-ingestion/scenarios/small-batch.yaml"));
        var original = parse(source);
        ExperimentDescriptors.PIPELINE.validate(original);
        var fields = new java.util.HashMap<String,Object>(original.parameters());
        for (Object value : List.of(25, 0, 60001, "25")) {
            fields.put("flushIntervalMs",value);
            var candidate = new ScenarioDefinition(original.schemaVersion(),original.experiment(),original.scenario(),fields,original.measurement(),original.input());
            if (value.equals(25)) ExperimentDescriptors.PIPELINE.validate(candidate);
            else assertThrows(ScenarioException.class, () -> ExperimentDescriptors.PIPELINE.validate(candidate));
        }
    }

    @Test void retryOptionsValidateBoundsAndTypes() throws Exception {
        var original = parse(java.nio.file.Files.readString(java.nio.file.Path.of("experiments/pipeline/excel-ingestion/scenarios/small-batch.yaml")));
        for (String key : List.of("maxAttempts", "retryBackoffMs")) {
            for (Object value : List.of(1, 0, key.equals("maxAttempts") ? 11 : 60001, "2")) {
                var fields = new java.util.HashMap<String,Object>(original.parameters());
                fields.put(key, value);
                var candidate = new ScenarioDefinition(original.schemaVersion(), original.experiment(), original.scenario(), fields, original.measurement(), original.input());
                if (value.equals(1)) ExperimentDescriptors.PIPELINE.validate(candidate);
                else assertThrows(ScenarioException.class, () -> ExperimentDescriptors.PIPELINE.validate(candidate));
            }
        }
    }

    @Test
    void canonicalHashIsStableAcrossYamlJsonOrderAndWhitespace() throws Exception {
        var definition = parse(YAML);
        ExperimentDescriptors.QUEUE.validate(definition);
        var json = documents.read(new ByteArrayInputStream(documents.canonical(definition).getBytes(StandardCharsets.UTF_8)), ScenarioDocuments.Format.JSON);
        assertEquals(documents.hash(definition), documents.hash(json));
        assertEquals(documents.hash(definition), documents.hash(parse("\n# comment\n" + YAML)));
        assertNotEquals(documents.hash(definition), documents.hash(parse(YAML.replace("queueCapacity: 2", "queueCapacity: 3"))));
        assertThrows(UnsupportedOperationException.class, () -> definition.parameters().put("queueCapacity", 99));
    }
    @Test
    void rejectsAmbiguousOrUnsupportedDefinitionsAndParameterCoercion() {
        for (String invalid : List.of(
                YAML + "schemaVersion: 2\n", YAML.replace("schemaVersion: 1", "schemaVersion: 2"),
                YAML.replace("mode: SINGLE_PASS", "mode: SINGLE_PASS, warmup: 10s"),
                YAML + "unknown: true\n", "!!java.lang.ProcessBuilder {}", YAML + "---\na: 1\n")) {
            assertThrows(ScenarioException.class, () -> parse(invalid), invalid);
        }
        for (String invalid : List.of(YAML.replace("queueCapacity: 2", "queueCapacity: '2'"),
                YAML.replace("queueCapacity: 2", "queueCapacity: 2.5"), YAML.replace("queueCapacity: 2", "queueCapacity: 0"),
                YAML.replace("queueCapacity: 2", "queueCapacity: 200000"), YAML.replace("queueCapacity: 2", "queueCapacity: 2, extra: 1"))) {
            assertThrows(ScenarioException.class, () -> ExperimentDescriptors.QUEUE.validate(parse(invalid)));
        }
    }
    @Test
    void rejectsDuplicateJsonKeysTrailingValuesAndOversizedDocuments() {
        assertThrows(ScenarioException.class, () -> documents.read(new ByteArrayInputStream("{\"schemaVersion\":1,\"schemaVersion\":1}".getBytes()), ScenarioDocuments.Format.JSON));
        assertThrows(ScenarioException.class, () -> documents.read(new ByteArrayInputStream("{} {}".getBytes()), ScenarioDocuments.Format.JSON));
        assertThrows(ScenarioException.class, () -> new ScenarioDocuments(10).read(new ByteArrayInputStream(YAML.getBytes()), ScenarioDocuments.Format.YAML));
    }
    @Test
    void rejectsDuplicateRegistryIdsAndUnknownExperiments() {
        var entry = new ExperimentRegistration<>(ExperimentDescriptors.QUEUE, d -> new com.pnomeer.lab.experiments.concurrency.QueueContentionExperiment(),
                (d, bytes) -> new com.pnomeer.lab.experiments.concurrency.QueueContentionScenario("unused",1,1,1,1,0));
        assertThrows(IllegalArgumentException.class, () -> new ExperimentRegistry(List.of(entry, entry)));
        assertThrows(ScenarioException.class, () -> new ExperimentRegistry(List.of(entry)).get("unknown"));
    }
}
