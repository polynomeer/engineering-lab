package com.pnomeer.lab.app.run;

import com.pnomeer.lab.registry.ExperimentRegistry;
import com.pnomeer.lab.scenario.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import java.io.IOException;
import java.net.URI;

@RestController
@RequestMapping("/runs")
public class ScenarioRunController {
    private final ScenarioRunService service;
    private final ExperimentRegistry registry;
    private final ScenarioDocuments documents;
    private final ScenarioProperties properties;
    public ScenarioRunController(ScenarioRunService service, ExperimentRegistry registry, ScenarioDocuments documents, ScenarioProperties properties) {
        this.service = service; this.registry = registry; this.documents = documents; this.properties = properties;
    }
    @PostMapping(consumes={"application/json", "application/yaml", "application/x-yaml", "text/yaml"})
    public ResponseEntity<Submission> submit(HttpServletRequest request) throws IOException {
        try (var input = request.getInputStream()) {
            var definition = documents.read(input, request.getContentType().contains("yaml") ? ScenarioDocuments.Format.YAML : ScenarioDocuments.Format.JSON);
            registry.validate(definition);
            return response(service.submit(definition, null), definition);
        }
    }
    @PostMapping(consumes="multipart/form-data")
    public ResponseEntity<Submission> upload(@RequestPart("scenario") MultipartFile scenario,
            @RequestPart(value="file", required=false) MultipartFile file) throws IOException {
        ScenarioDefinition definition;
        try (var input = scenario.getInputStream()) {
            String type = scenario.getContentType();
            var format = type != null && type.contains("yaml") ? ScenarioDocuments.Format.YAML
                    : type != null && type.contains("json") ? ScenarioDocuments.Format.JSON
                    : ScenarioDocuments.format(scenario.getOriginalFilename() == null ? "" : scenario.getOriginalFilename());
            definition = documents.read(input, format);
        }
        registry.validate(definition);
        if (definition.input() == null && file != null) throw new ScenarioException("This experiment does not accept a file");
        byte[] payload = null;
        if (file != null) {
            try (var stream = file.getInputStream()) { payload = ScenarioDocuments.readBounded(stream, properties.maxInputBytes()); }
        }
        return response(service.submit(definition, payload), definition);
    }
    private ResponseEntity<Submission> response(String id, ScenarioDefinition definition) {
        return ResponseEntity.accepted().location(URI.create("/runs/" + id))
                .body(new Submission(id, definition.experiment(), definition.scenario().id(), documents.hash(definition)));
    }
    public record Submission(String runId, String experimentType, String scenarioId, String definitionSha256) { }
}
