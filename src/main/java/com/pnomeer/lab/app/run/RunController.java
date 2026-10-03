package com.pnomeer.lab.app.run;

import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.StorageProperties;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.List;

@RestController
@RequestMapping("/runs")
public class RunController {
    private final RunStore store;
    private final StorageProperties properties;
    public RunController(RunStore store, StorageProperties properties) { this.store = store; this.properties = properties; }

    @GetMapping
    public RunPage list(@RequestParam(required=false) String experimentType,
            @RequestParam(defaultValue="9223372036854775807") long before,
            @RequestParam(required=false) Integer limit) {
        limit = limit == null ? Math.min(100, properties.pageLimit()) : limit;
        validateLimit(limit);
        if (before < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "before must be positive");
        var runs = store.list(experimentType, before, limit);
        return new RunPage(runs, runs.size() == limit ? runs.getLast().sequence() : null);
    }
    @GetMapping("/{runId}")
    public StoredRun get(@PathVariable String runId) {
        return store.find(runId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found"));
    }
    @GetMapping("/{runId}/metrics")
    public MetricPage metrics(@PathVariable String runId, @RequestParam(defaultValue="-1") long after,
            @RequestParam(required=false) Integer limit) {
        get(runId);
        limit = limit == null ? Math.min(100, properties.pageLimit()) : limit;
        validateLimit(limit);
        if (after < -1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "after must be >= -1");
        var metrics = store.metrics(runId, after, limit);
        return new MetricPage(metrics, metrics.size() == limit ? metrics.getLast().sequence() : null);
    }
    @GetMapping("/{runId}/traces")
    public MetricPage traces(@PathVariable String runId, @RequestParam(defaultValue="-1") long after,
            @RequestParam(required=false) Integer limit) {
        var page = metrics(runId, after, limit);
        return new MetricPage(page.metrics().stream().filter(m -> "trace.span".equals(m.point().labels().get("source"))).toList(), page.nextAfter());
    }
    private void validateLimit(int limit) {
        if (limit < 1 || limit > properties.pageLimit()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page limit");
    }
    public record RunPage(List<StoredRun> runs, Long nextBefore) { }
    public record MetricPage(List<StoredMetric> metrics, Long nextAfter) { }
}
