package com.pnomeer.lab.app.series;

import com.pnomeer.lab.core.input.InputPayload;
import com.pnomeer.lab.core.storage.RunSweep;
import com.pnomeer.lab.registry.ExperimentRegistry;
import com.pnomeer.lab.scenario.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Body: {"definition": <scenario document>, "grid": {"param": [values...]}}. Excel sweeps use multipart
 * parts "scenario", "grid" (JSON object) and "file".
 */
@RestController
@RequestMapping("/run-sweeps")
public class RunSweepController {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RunSweepService service;
    private final ExperimentRegistry registry;
    private final ScenarioDocuments documents;
    private final ScenarioProperties properties;
    public RunSweepController(RunSweepService service, ExperimentRegistry registry, ScenarioDocuments documents, ScenarioProperties properties) {
        this.service = service; this.registry = registry; this.documents = documents; this.properties = properties;
    }

    @PostMapping(consumes="application/json")
    public ResponseEntity<RunSweep> submit(@RequestBody Map<String, Object> body,
            @RequestParam(defaultValue="3") int repetitions, @RequestParam(defaultValue="0") int warmup) throws IOException {
        if (body.keySet().stream().anyMatch(k -> !k.equals("definition") && !k.equals("grid")))
            throw new ScenarioException("sweep body allows only definition and grid");
        var definition = read(JSON.writeValueAsBytes(body.get("definition")), ScenarioDocuments.Format.JSON);
        if (definition.input() != null) throw new ScenarioException("This experiment needs a multipart upload with a file part");
        return response(service.submit(definition, grid(body.get("grid")), null, repetitions, warmup));
    }

    @PostMapping(consumes="multipart/form-data")
    public ResponseEntity<RunSweep> upload(@RequestPart("scenario") MultipartFile scenario, @RequestPart("grid") String grid,
            @RequestPart(value="file", required=false) MultipartFile file,
            @RequestParam(defaultValue="3") int repetitions, @RequestParam(defaultValue="0") int warmup) throws IOException {
        String type = scenario.getContentType();
        var definition = read(scenario.getBytes(), type != null && type.contains("yaml") ? ScenarioDocuments.Format.YAML
                : type != null && type.contains("json") ? ScenarioDocuments.Format.JSON
                : ScenarioDocuments.format(scenario.getOriginalFilename() == null ? "" : scenario.getOriginalFilename()));
        if (definition.input() == null && file != null) throw new ScenarioException("This experiment does not accept a file");
        if (definition.input() != null && file == null) throw new ScenarioException("This experiment needs a file part");
        Map<?, ?> rawGrid;
        try { rawGrid = JSON.readValue(grid, Map.class); }
        catch (RuntimeException ex) { throw new ScenarioException("grid must be a JSON object"); }
        var parsed = grid(rawGrid);
        InputPayload payload = null;
        if (file != null) {
            try { payload = InputPayload.stage(file.getInputStream(), properties.maxInputBytes()); }
            catch (InputPayload.LimitExceeded ex) { throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex); }
            catch (InputPayload.CapacityExceeded ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex); }
        }
        return response(service.submit(definition, parsed, payload, repetitions, warmup));
    }

    @GetMapping
    public List<RunSweep> list(@RequestParam(required=false) String experimentType, @RequestParam(defaultValue="20") int limit) {
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..100");
        return service.list(experimentType, limit);
    }

    @PostMapping("/{sweepId}/cancel")
    public ResponseEntity<com.pnomeer.lab.core.storage.RunSweep> cancel(@PathVariable String sweepId) {
        return ResponseEntity.accepted().body(service.cancel(sweepId));
    }

    @GetMapping("/{sweepId}")
    public RunSweepService.SweepView get(@PathVariable String sweepId) {
        return service.view(sweepId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown sweep"));
    }

    private ScenarioDefinition read(byte[] bytes, ScenarioDocuments.Format format) throws IOException {
        if (bytes.length == 0 || new String(bytes, StandardCharsets.UTF_8).equals("null")) throw new ScenarioException("definition is required");
        var definition = documents.read(new ByteArrayInputStream(bytes), format);
        registry.validate(definition);
        return definition;
    }

    private static Map<String, List<Object>> grid(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) throw new ScenarioException("grid must be an object of parameter value lists");
        return SweepGrid.normalize(map);
    }

    private static ResponseEntity<RunSweep> response(RunSweep sweep) {
        return ResponseEntity.accepted().location(URI.create("/run-sweeps/" + sweep.sweepId())).body(sweep);
    }
}
