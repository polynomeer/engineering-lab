package com.pnomeer.lab.app.run;

import com.pnomeer.lab.registry.ExperimentCatalog;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class ModuleCatalogController {
    private final ExperimentCatalog catalog;
    public ModuleCatalogController(ExperimentCatalog catalog) { this.catalog = catalog; }
    @GetMapping("/modules")
    public List<ExperimentCatalog.ModuleEntry> modules() { return catalog.modules(); }
}
