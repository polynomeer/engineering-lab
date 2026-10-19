package com.pnomeer.lab.app.dashboard;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class DashboardController {
    // Every client route of frontend/src/api.ts parseRoute; assets stay with the static resource handler.
    @GetMapping(value={"/app", "/app/", "/app/runs", "/app/runs/{id}", "/app/compare", "/app/settings",
            "/app/experiments", "/app/experiments/{id}", "/app/tools/ingestion-db", "/app/tools/io-echo",
            "/app/catalog", "/app/career", "/app/series", "/app/series/compare", "/app/series/{id}", "/app/sweeps/{id}"}, produces="text/html")
    public org.springframework.http.ResponseEntity<Resource> page() {
        var page = new ClassPathResource("static/app/index.html");
        if (!page.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This API-only build has no dashboard; build bootJar without -PskipFrontend");
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(page);
    }
}
