package com.pnomeer.lab.app.run;

import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.registry.measurement.MeasurementCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/dashboard/v1")
public class MeasurementController {
    private final RunStore store;
    private final MeasurementCatalog catalog;
    public MeasurementController(RunStore store, MeasurementCatalog catalog) { this.store = store; this.catalog = catalog; }
    @GetMapping("/measurement-catalog") public MeasurementCatalog.Snapshot catalog() { return catalog.snapshot(); }
    @GetMapping("/comparison-assessment") public ComparisonAssessment compare(@RequestParam String baseline, @RequestParam String candidate) {
        return ComparisonAssessment.of(run(baseline), run(candidate), store.execution(baseline).orElse(null), store.execution(candidate).orElse(null));
    }
    private StoredRun run(String id) { return store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found")); }
}
