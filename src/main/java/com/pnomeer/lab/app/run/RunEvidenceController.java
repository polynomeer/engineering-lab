package com.pnomeer.lab.app.run;

import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.StorageProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/runs")
public class RunEvidenceController {
    private final RunStore store;
    private final int pageSize;
    private final JsonMapper json = JsonMapper.builder().build();
    public RunEvidenceController(RunStore store, StorageProperties properties) {
        this.store = store; this.pageSize = Math.min(100, properties.pageLimit());
    }
    private StoredRun get(String id) {
        return store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }
    @GetMapping("/compare")
    public RunComparison compare(@RequestParam String baseline, @RequestParam String candidate) {
        return RunComparison.of(get(baseline), get(candidate), store.execution(baseline).orElse(null), store.execution(candidate).orElse(null));
    }
    @GetMapping("/{id}/artifacts")
    public List<ArtifactReference> artifacts(@PathVariable String id) { get(id); return store.artifacts(id); }

    @PutMapping(value="/{id}/artifacts/{artifactId}", consumes="application/json")
    public ArtifactReference register(@PathVariable String id, @PathVariable String artifactId, HttpServletRequest request) throws IOException {
        get(id);
        byte[] bytes = request.getInputStream().readNBytes(8193);
        if (bytes.length > 8192) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Artifact metadata exceeds 8 KiB");
        try {
            var body = json.readValue(bytes, ReferenceBody.class);
            if (body == null || body.byteSize() == null) throw new IllegalArgumentException("byteSize is required");
            var reference = new ArtifactReference(artifactId, id, body.relativePath(), body.mediaType(), body.byteSize(), body.sha256());
            store.registerArtifact(reference);
            return reference;
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or conflicting artifact reference", ex);
        }
    }
    public record ReferenceBody(String relativePath, String mediaType, Long byteSize, String sha256) { }

    @GetMapping(value="/{id}/export", produces="application/x-ndjson")
    public void export(@PathVariable String id, HttpServletResponse response) throws IOException {
        var run = get(id);
        if (run.status() == RunStatus.QUEUED || run.status() == RunStatus.RUNNING) throw new ResponseStatusException(HttpStatus.CONFLICT, "Export requires a terminal Run");
        // Read references once so concurrent registrations cannot alter this export's reference set.
        var references = store.artifacts(id);
        response.setContentType("application/x-ndjson");
        response.setHeader("Content-Disposition", "attachment; filename=run-evidence.ndjson");
        response.setHeader("Cache-Control", "no-store");
        var out = response.getOutputStream();
        line(out, Map.of("type", "run", "schemaVersion", 1, "data", run));
        long cursor = -1, count = 0;
        while (true) {
            var page = store.metrics(id, cursor, pageSize);
            for (var metric : page) { line(out, Map.of("type", "metric", "data", metric)); count++; cursor = metric.sequence(); }
            if (page.size() < pageSize) break;
        }
        for (var reference : references) line(out, Map.of("type", "artifact-reference", "data", reference));
        line(out, Map.of("type", "end", "runId", id, "metricCount", count, "artifactCount", references.size()));
        out.flush();
    }
    private void line(java.io.OutputStream out, Object record) throws IOException {
        out.write(json.writeValueAsBytes(record)); out.write('\n');
    }
}
