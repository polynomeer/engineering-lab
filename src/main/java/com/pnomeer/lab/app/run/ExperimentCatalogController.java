package com.pnomeer.lab.app.run;

import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.scenario.ScenarioException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@RestController
@RequestMapping("/experiments")
public class ExperimentCatalogController {
    private final ExperimentRegistry registry;
    public ExperimentCatalogController(ExperimentRegistry registry) { this.registry = registry; }
    @GetMapping public List<ExperimentDescriptor> list() { return registry.list(); }
    @GetMapping("/{experimentId}") public ExperimentDescriptor get(@PathVariable String experimentId) {
        try { return registry.get(experimentId); }
        catch (ScenarioException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage()); }
    }
}
