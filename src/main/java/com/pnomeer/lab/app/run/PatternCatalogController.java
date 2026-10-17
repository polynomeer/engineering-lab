package com.pnomeer.lab.app.run;

import com.pnomeer.lab.registry.pattern.PatternCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PatternCatalogController {
    private final PatternCatalog catalog;
    public PatternCatalogController(PatternCatalog catalog) { this.catalog = catalog; }
    @GetMapping("/dashboard/v1/pattern-catalog")
    public PatternCatalog.Snapshot catalog() { return catalog.snapshot(); }
}
