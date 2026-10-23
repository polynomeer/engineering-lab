package com.pnomeer.lab.app.run;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RunUiController {
    private final int pageSize;
    private final long artifactDownloadMaxBytes;
    public RunUiController(com.pnomeer.lab.storage.StorageProperties properties,
            com.pnomeer.lab.storage.ArtifactDownloadProperties downloads) {
        this.pageSize = Math.min(20, properties.pageLimit());
        this.artifactDownloadMaxBytes = downloads.maxBytes();
    }
    @GetMapping("/runs/ui/options")
    public java.util.Map<String, Number> options() { return java.util.Map.of("workerReportDownload", 1, "pageSize", pageSize, "artifactDownloadMaxBytes", artifactDownloadMaxBytes); }
}
