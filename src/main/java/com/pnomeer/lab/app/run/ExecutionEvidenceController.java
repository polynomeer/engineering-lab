package com.pnomeer.lab.app.run;

import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.StorageProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.*;

/** Additive reads; old /runs payloads and export schema remain unchanged. */
@RestController
@RequestMapping("/dashboard/v1/runs")
public class ExecutionEvidenceController {
    private final RunStore store;
    private final int pageSize;
    private final JsonMapper json = JsonMapper.builder().build();
    public ExecutionEvidenceController(RunStore store, StorageProperties properties) {
        this.store = store; this.pageSize = Math.min(RunCheck.MAX_PAGE, properties.pageLimit());
    }
    public record ExecutionView(String runId, boolean available, RunExecution execution) {}
    public record CheckPage(String runId, boolean available, List<RunCheck> checks, Long nextAfter) {}

    @GetMapping("/{id}/execution")
    public ExecutionView execution(@PathVariable String id) {
        get(id);
        var execution = store.execution(id).orElse(null);
        return new ExecutionView(id, execution != null, execution);
    }
    @GetMapping("/{id}/checks")
    public CheckPage checks(@PathVariable String id, @RequestParam(defaultValue="-1") long after,
            @RequestParam(required=false) Integer limit) {
        terminal(get(id));
        int size = limit == null ? pageSize : limit;
        if (after < -1 || size < 1 || size > pageSize) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid check page");
        var checks = store.checks(id, after, size);
        return new CheckPage(id, store.execution(id).isPresent(), checks, checks.size() == size ? checks.getLast().sequence() : null);
    }
    @GetMapping(value="/{id}/export", produces="application/x-ndjson")
    public void export(@PathVariable String id, HttpServletResponse response) throws IOException {
        var run = get(id); terminal(run);
        var execution = execution(id);
        var references = store.artifacts(id);
        response.setContentType("application/x-ndjson");
        response.setHeader("Content-Disposition", "attachment; filename=run-evidence-v2.ndjson");
        response.setHeader("Cache-Control", "no-store");
        var out = response.getOutputStream();
        line(out, Map.of("type", "run", "schemaVersion", 2, "data", run));
        line(out, Map.of("type", "execution", "data", execution));
        long cursor = -1, metricCount = 0, checkCount = 0;
        while (true) {
            var page = store.metrics(id, cursor, pageSize);
            for (var metric : page) { line(out, Map.of("type", "metric", "data", metric)); cursor = metric.sequence(); metricCount++; }
            if (page.size() < pageSize) break;
        }
        cursor = -1;
        while (true) {
            var page = store.checks(id, cursor, pageSize);
            for (var check : page) { line(out, Map.of("type", "check", "data", check)); cursor = check.sequence(); checkCount++; }
            if (page.size() < pageSize) break;
        }
        for (var reference : references) line(out, Map.of("type", "artifact-reference", "data", reference));
        line(out, Map.of("type", "end", "runId", id, "metricCount", metricCount, "checkCount", checkCount, "artifactCount", references.size()));
        out.flush();
    }
    private StoredRun get(String id) {
        return store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }
    private void terminal(StoredRun run) {
        if (run.status() == RunStatus.QUEUED || run.status() == RunStatus.RUNNING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Check paging and export require a terminal Run");
        }
    }
    private void line(java.io.OutputStream out, Object record) throws IOException {
        out.write(json.writeValueAsBytes(record)); out.write('\n');
    }
}
