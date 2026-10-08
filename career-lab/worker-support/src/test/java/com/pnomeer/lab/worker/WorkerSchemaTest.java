package com.pnomeer.lab.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Validate the published schemas with a standards validator, independently of the wire codec. */
class WorkerSchemaTest {
    private static final Path CONTRACTS = Path.of(System.getProperty("worker.contracts"));
    private final ObjectMapper mapper = new ObjectMapper();
    private JsonSchema schema(String name) throws Exception {
        try (var input = Files.newInputStream(CONTRACTS.resolve(name + ".schema.json"))) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(input);
        }
    }
    @Test void publishedSchemasAcceptBothCompleteOutcomesAndCommand() throws Exception {
        var command = mapper.readTree(CONTRACTS.resolve("fixtures/command.json").toFile());
        assertTrue(schema("command").validate(command).isEmpty());
        var eventSchema = schema("event");
        for (String name : new String[]{"success", "failure"}) {
            for (String line : Files.readAllLines(CONTRACTS.resolve("fixtures/" + name + ".ndjson"))) {
                var errors = eventSchema.validate(mapper.readTree(line));
                assertTrue(errors.isEmpty(), errors::toString);
            }
        }
    }
    @Test void publishedSchemaRejectsUnknownVersionsUnsafePathsAndWrongTypes() throws Exception {
        var eventSchema = schema("event");
        var lines = Files.readAllLines(CONTRACTS.resolve("fixtures/success.ndjson"));
        ObjectNode hello = (ObjectNode) mapper.readTree(lines.getFirst()); hello.put("protocolVersion", 2);
        assertFalse(eventSchema.validate(hello).isEmpty());
        for (String path : new String[]{"../outside", "/tmp/outside", "a/../b", "a//b", "a\\b", "C:/b"}) {
            ObjectNode artifact = (ObjectNode) mapper.readTree(lines.get(6));
            ((ObjectNode) artifact.path("payload")).put("path", path);
            assertFalse(eventSchema.validate(artifact).isEmpty(), path);
        }
        ObjectNode command = (ObjectNode) mapper.readTree(CONTRACTS.resolve("fixtures/command.json").toFile());
        command.put("seed", "42"); assertFalse(schema("command").validate(command).isEmpty());
    }
}
