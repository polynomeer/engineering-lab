package com.pnomeer.lab.app.run;

import com.pnomeer.lab.core.storage.RunCheck;
import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.runtime.*;
import com.pnomeer.lab.storage.StorageProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Reports only wired platform features; restored source modules are not runnable workers. */
@RestController
@RequestMapping("/dashboard/v1")
public class DashboardCatalogController {
    private final ExperimentCatalog catalog;
    private final Map<String, Boolean> features;
    private final Map<String, Integer> limits;
    public DashboardCatalogController(ExperimentCatalog catalog, StorageProperties storage,
            ObservationProperties observation, TraceProperties trace, JfrProperties jfr, RunLogProperties logs) {
        this.catalog = catalog;
        this.features = Map.ofEntries(Map.entry("executionEvidence", true), Map.entry("checksRead", true),
                Map.entry("extendedExport", true), Map.entry("executionComparison", true),
                Map.entry("localJvm", false), Map.entry("cancellation", false),
                Map.entry("jvmObservation", observation.jvmEnabled()), Map.entry("trace", trace.enabled()),
                Map.entry("jfr", jfr.enabled()), Map.entry("runLogs", logs.enabled()));
        this.limits = Map.of("checkPageSize", Math.min(RunCheck.MAX_PAGE, storage.pageLimit()),
                "maxChecksPerRun", RunCheck.MAX_CHECKS, "maxCheckBytes", RunCheck.MAX_BYTES,
                "maxActiveRuns", storage.maxActiveRuns());
    }
    public record Capabilities(int schemaVersion, List<String> executionKinds, List<Integer> workerProtocolVersions,
                               Map<String, Boolean> features, Map<String, Integer> limits) {}
    public record ModuleView(ModuleDescriptor module, String readiness, String reason, List<String> experimentIds) {}
    public record ExperimentView(String experimentId, String moduleId, String descriptorUrl, String readiness,
                                 String executionKind, List<String> variants, List<String> presentation,
                                 Map<String, Boolean> capabilities) {}

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return new Capabilities(1, List.of("IN_PROCESS"), List.of(), features, limits);
    }
    @GetMapping("/modules")
    public List<ModuleView> modules() {
        return catalog.modules().stream().map(entry -> new ModuleView(entry.module(), "READY", null,
                entry.experiments().stream().map(ExperimentDescriptor::id).toList())).toList();
    }
    @GetMapping("/experiments/{id}")
    public ExperimentView experiment(@PathVariable String id) {
        for (var entry : catalog.modules()) {
            for (var descriptor : entry.experiments()) {
                if (descriptor.id().equals(id)) {
                    var supported = new HashMap<>(features);
                    supported.put("checksProduced", false);
                    supported.put("fileUpload", "XLSX_UPLOAD".equals(descriptor.input()));
                    return new ExperimentView(id, entry.module().id(), "/experiments/" + id, "READY", "IN_PROCESS",
                            List.of(), List.of("SUMMARY", "METRICS", "EXECUTION"), Map.copyOf(supported));
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found");
    }
}
