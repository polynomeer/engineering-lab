package com.pnomeer.lab.app.run;

import com.pnomeer.lab.metrics.MetricCatalog;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class MetricCatalogController {
    private final Map<String, MetricCatalog.Definition> metrics;
    public MetricCatalogController(com.pnomeer.lab.registry.ExperimentCatalog catalog) { metrics = MetricCatalog.including(catalog); }
    @GetMapping("/metrics/schema")
    public Map<String, Object> schema() {
        return Map.of("version", MetricCatalog.VERSION, "metrics", metrics.values(),
                "note", "Legacy counters/gauges are storage containers, not semantic types. Unknown metrics retain unknown units; no conversion is applied.");
    }
}
