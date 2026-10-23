package com.pnomeer.lab.app.findings;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Read-only view of which Finding documents cite which runs. */
@RestController
public class FindingController {
    private final FindingIndex index;
    public FindingController(FindingIndex index) { this.index = index; }

    public record Summary(String file, String title, int citedRuns) { }
    public record Citation(String file, String title) { }

    @GetMapping("/findings")
    public List<Summary> list() {
        return index.findings().stream().map(f -> new Summary(f.file(), f.title(), f.runIds().size())).toList();
    }

    @GetMapping("/findings/{file}")
    public ResponseEntity<String> read(@PathVariable String file) {
        var text = index.read(file).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown Finding"));
        return ResponseEntity.ok().contentType(new MediaType("text", "markdown", java.nio.charset.StandardCharsets.UTF_8))
                .header("X-Content-Type-Options", "nosniff").body(text);
    }

    @GetMapping("/runs/{runId}/findings")
    public List<Citation> citing(@PathVariable String runId) {
        return index.citing(runId).stream().map(f -> new Citation(f.file(), f.title())).toList();
    }
}
