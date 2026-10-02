package com.pnomeer.lab.app.run;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RunUiController {
    private final int pageSize;
    public RunUiController(com.pnomeer.lab.storage.StorageProperties properties) { this.pageSize = Math.min(20, properties.pageLimit()); }
    @GetMapping("/runs/ui/options")
    public java.util.Map<String, Integer> options() { return java.util.Map.of("pageSize", pageSize); }
    @GetMapping(value="/runs/ui", produces="text/html")
    public Resource ui() { return new ClassPathResource("static/run-browser/index.html"); }
}
