package com.pnomeer.lab.runtime;

import java.util.*;

/** Trusted image contains /opt/lab/worker.jar and a Java 21 runtime. No scenario-controlled launch options. */
public record ContainerWorkerManifest(String imageId, String artifactSha256, String moduleId, Set<String> experiments) {
    public ContainerWorkerManifest {
        experiments = Set.copyOf(experiments);
        if (imageId == null || !imageId.matches("sha256:[a-f0-9]{64}")
                || artifactSha256 == null || !artifactSha256.matches("[a-f0-9]{64}")
                || moduleId == null || !moduleId.matches("[a-zA-Z0-9._-]{1,100}") || experiments.isEmpty()
                || experiments.size() > 64 || experiments.stream().anyMatch(e -> !e.matches("[a-zA-Z0-9._-]{1,100}")))
            throw new IllegalArgumentException("Invalid container worker manifest");
    }
}
