package com.pnomeer.lab.app.run;

import com.pnomeer.lab.registry.pattern.StudyCatalog;
import org.springframework.web.bind.annotation.*;

@RestController
public class StudyCatalogController {
    private final StudyCatalog catalog;
    public StudyCatalogController(StudyCatalog catalog) { this.catalog = catalog; }
    @GetMapping("/dashboard/v1/study-catalog")
    public StudyCatalog.Snapshot catalog() { return catalog.snapshot(); }
}
