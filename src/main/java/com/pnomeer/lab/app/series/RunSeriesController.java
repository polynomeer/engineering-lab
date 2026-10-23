package com.pnomeer.lab.app.series;

import com.pnomeer.lab.core.input.InputPayload;
import com.pnomeer.lab.core.storage.RunSeries;
import com.pnomeer.lab.registry.ExperimentRegistry;
import com.pnomeer.lab.scenario.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/** Same scenario documents as POST /runs; repetition counts are query parameters, outside the definition hash. */
@RestController
@RequestMapping("/run-series")
public class RunSeriesController {
    private final RunSeriesService service;
    private final ExperimentRegistry registry;
    private final ScenarioDocuments documents;
    private final ScenarioProperties properties;
    public RunSeriesController(RunSeriesService service, ExperimentRegistry registry, ScenarioDocuments documents, ScenarioProperties properties) {
        this.service = service; this.registry = registry; this.documents = documents; this.properties = properties;
    }

    @PostMapping(consumes={"application/json", "application/yaml", "application/x-yaml", "text/yaml"})
    public ResponseEntity<Submission> submit(HttpServletRequest request,
            @RequestParam(defaultValue="5") int repetitions, @RequestParam(defaultValue="1") int warmup) throws IOException {
        bounds(repetitions, warmup);
        try (var input = request.getInputStream()) {
            var definition = documents.read(input, request.getContentType().contains("yaml") ? ScenarioDocuments.Format.YAML : ScenarioDocuments.Format.JSON);
            registry.validate(definition);
            if (definition.input() != null) throw new ScenarioException("This experiment needs a multipart upload with a file part");
            return response(service.submit(definition, null, repetitions, warmup));
        }
    }

    @PostMapping(consumes="multipart/form-data")
    public ResponseEntity<Submission> upload(@RequestPart("scenario") MultipartFile scenario,
            @RequestPart(value="file", required=false) MultipartFile file,
            @RequestParam(defaultValue="5") int repetitions, @RequestParam(defaultValue="1") int warmup) throws IOException {
        bounds(repetitions, warmup);
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
        if (definition.input() != null && file == null) throw new ScenarioException("This experiment needs a file part");
        InputPayload payload = null;
        if (file != null) {
            try { payload = InputPayload.stage(file.getInputStream(), properties.maxInputBytes()); }
            catch (InputPayload.LimitExceeded ex) { throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), ex); }
            catch (InputPayload.CapacityExceeded ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex); }
        }
        return response(service.submit(definition, payload, repetitions, warmup));
    }

    @GetMapping
    public List<RunSeries> list(@RequestParam(required=false) String experimentType, @RequestParam(defaultValue="20") int limit) {
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..100");
        return service.list(experimentType, limit);
    }

    @GetMapping("/{seriesId}")
    public SeriesView get(@PathVariable String seriesId) {
        return service.view(seriesId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown series"));
    }

    @GetMapping("/compare")
    public SeriesComparison compare(@RequestParam String baseline, @RequestParam String candidate) {
        return service.compare(baseline, candidate);
    }

    @PutMapping("/{seriesId}/baseline")
    public RunSeries pinBaseline(@PathVariable String seriesId) { return service.pinBaseline(seriesId); }

    @GetMapping("/baselines")
    public List<RunSeries> baselines(@RequestParam(defaultValue="50") int limit) {
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..100");
        return service.baselines(limit);
    }

    @GetMapping("/{seriesId}/regression")
    public RegressionCheck regression(@PathVariable String seriesId,
            @RequestParam(defaultValue="5") double tolerancePct) {
        return service.regression(seriesId, tolerancePct);
    }

    @GetMapping("/{seriesId}/baseline-file")
    public ResponseEntity<BaselineFile> baselineFile(@PathVariable String seriesId) {
        var file = service.baselineFile(seriesId);
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=" + file.scenarioId() + ".baseline.json").body(file);
    }

    /** Regression check against a baseline file in the body, e.g. one committed for CI. */
    @PostMapping(value = "/{seriesId}/regression", consumes = "application/json")
    public RegressionCheck regressionAgainstFile(@PathVariable String seriesId, @RequestBody BaselineFile baseline,
            @RequestParam(defaultValue="5") double tolerancePct) {
        return service.regression(seriesId, baseline, tolerancePct);
    }

    private static void bounds(int repetitions, int warmup) {
        if (repetitions < 1 || repetitions > RunSeries.MAX_REPETITIONS || warmup < 0 || warmup > RunSeries.MAX_WARMUP)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "repetitions must be 1.." + RunSeries.MAX_REPETITIONS + " and warmup 0.." + RunSeries.MAX_WARMUP);
    }

    private ResponseEntity<Submission> response(RunSeries series) {
        return ResponseEntity.accepted().location(URI.create("/run-series/" + series.seriesId()))
                .body(new Submission(series.seriesId(), series.experimentType(), series.scenarioId(), series.definitionSha256(),
                        series.repetitions(), series.warmup()));
    }
    public record Submission(String seriesId, String experimentType, String scenarioId, String definitionSha256, int repetitions, int warmup) { }
}
