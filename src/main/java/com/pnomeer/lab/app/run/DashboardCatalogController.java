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
    private final Map<String, WorkerRegistration> workers;
    private final Map<String, Boolean> features;
    private final Map<String, Integer> limits;
    public DashboardCatalogController(ExperimentCatalog catalog, StorageProperties storage,
            ObservationProperties observation, TraceProperties trace, JfrProperties jfr, RunLogProperties logs) {
        this(catalog, storage, observation, trace, jfr, logs, List.of());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DashboardCatalogController(ExperimentCatalog catalog, StorageProperties storage,
            ObservationProperties observation, TraceProperties trace, JfrProperties jfr, RunLogProperties logs,
            List<WorkerRegistration> registrations) {
        var workers = new HashMap<String, WorkerRegistration>();
        registrations.forEach(worker -> worker.experiments().forEach(id -> {
            if (workers.putIfAbsent(id, worker) != null) throw new IllegalArgumentException("Duplicate worker binding");
        }));
        this.workers = Map.copyOf(workers);
        this.catalog = catalog;
        this.features = Map.ofEntries(Map.entry("executionEvidence", true), Map.entry("checksRead", true),
                Map.entry("extendedExport", true), Map.entry("executionComparison", true),
                Map.entry("localJvm", workers.values().stream().anyMatch(w -> w.kind().name().equals("LOCAL_JVM"))), Map.entry("container", workers.values().stream().anyMatch(w -> w.kind().name().equals("CONTAINER"))), Map.entry("cancellation", false),
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
        return new Capabilities(1, java.util.stream.Stream.concat(java.util.stream.Stream.of("IN_PROCESS"), workers.values().stream().map(w -> w.kind().name()).distinct().sorted()).toList(),
                workers.isEmpty() ? List.of() : List.of(1), features, limits);
    }
    @GetMapping("/modules")
    public List<ModuleView> modules() {
        return catalog.modules().stream().map(entry -> {
            var state = entry.experiments().stream().map(ExperimentDescriptor::id).filter(workers::containsKey)
                    .map(id -> workers.get(id).readiness().get()).filter(r -> !r.ready()).findFirst()
                    .orElse(new LocalJvmRegistration.Readiness("READY", null));
            return new ModuleView(entry.module(), state.state(), state.reason(), entry.experiments().stream().map(ExperimentDescriptor::id).toList());
        }).toList();
    }
    @GetMapping("/experiments/{id}")
    public ExperimentView experiment(@PathVariable String id) {
        for (var entry : catalog.modules()) {
            for (var descriptor : entry.experiments()) {
                if (descriptor.id().equals(id)) {
                    var supported = new HashMap<>(features);
                    var worker = workers.get(id);
                    supported.put("localJvm", worker != null && worker.kind().name().equals("LOCAL_JVM"));
                    supported.put("container", worker != null && worker.kind().name().equals("CONTAINER"));
                    supported.put("checksProduced", worker != null);
                    if (worker != null) {
                        for (String key : List.of("jvmObservation", "trace", "jfr", "runLogs")) supported.put(key, false);
                    }
                    supported.put("fileUpload", "XLSX_UPLOAD".equals(descriptor.input()));
                    return new ExperimentView(id, entry.module().id(), "/experiments/" + id,
                            worker == null ? "READY" : worker.readiness().get().state(), worker == null ? "IN_PROCESS" : worker.kind().name(),
                            worker == null ? List.of() : worker.variants(), worker == null ? List.of("SUMMARY", "METRICS", "EXECUTION")
                                    : worker.producesMetrics() ? List.of("SUMMARY", "METRICS", "EXECUTION", "CHECK_RESULTS")
                                    : List.of("SUMMARY", "EXECUTION", "CHECK_RESULTS"), Map.copyOf(supported));
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Experiment not found");
    }
}
