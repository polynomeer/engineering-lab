package com.pnomeer.lab.core.storage;

/** Artifact metadata from a user or collector; registration itself does not verify or serve file bytes. */
public record ArtifactReference(String artifactId, String runId, String relativePath,
        String mediaType, long byteSize, String sha256) {
    public ArtifactReference {
        if (artifactId == null || !artifactId.matches("[a-zA-Z0-9_-]{1,100}") || runId == null
                || relativePath == null || relativePath.length() > 1000
                || !relativePath.matches("[a-zA-Z0-9_. /-]+") || relativePath.startsWith("/")
                || java.util.Arrays.stream(relativePath.split("/", -1)).anyMatch(p -> p.isBlank() || p.equals("..") || p.equals("."))
                || mediaType == null || !mediaType.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+") || mediaType.length() > 200
                || byteSize < 0 || sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid artifact reference metadata");
        }
    }
}
