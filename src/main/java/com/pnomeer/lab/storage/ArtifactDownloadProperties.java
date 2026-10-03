package com.pnomeer.lab.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab.artifacts.download")
public record ArtifactDownloadProperties(long maxBytes, int maxConcurrent) {
    public ArtifactDownloadProperties {
        if (maxBytes < 1 || maxBytes > 256L * 1024 * 1024 || maxConcurrent < 1 || maxConcurrent > 8) {
            throw new IllegalArgumentException("Artifact downloads require max-bytes 1..256 MiB and max-concurrent 1..8");
        }
    }
}
