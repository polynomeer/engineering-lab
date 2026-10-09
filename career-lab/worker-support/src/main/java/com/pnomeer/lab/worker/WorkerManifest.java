package com.pnomeer.lab.worker;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Server-owned launch definition, never populated from scenario parameters. One self-contained jar. */
public record WorkerManifest(Path javaExecutable, Path jar, String artifactSha256,
                             String moduleId, Set<String> experiments, int heapMiB) {
    public WorkerManifest {
        Objects.requireNonNull(javaExecutable); Objects.requireNonNull(jar);
        experiments = Set.copyOf(experiments);
        if (!javaExecutable.isAbsolute() || !jar.isAbsolute() || heapMiB < 32 || heapMiB > 8192
                || artifactSha256 == null || !artifactSha256.matches("[a-f0-9]{64}")
                || moduleId == null || !moduleId.matches("[a-zA-Z0-9._-]{1,100}") || experiments.isEmpty()
                || experiments.size() > 64 || experiments.stream().anyMatch(e -> !e.matches("[a-zA-Z0-9._-]{1,100}")))
            throw new IllegalArgumentException("Invalid worker manifest");
    }
    public void preflight(String experiment) throws IOException {
        if (!experiments.contains(experiment)) throw new ProtocolException("EXPERIMENT_NOT_ALLOWED");
        if (!Files.isRegularFile(javaExecutable) || !Files.isExecutable(javaExecutable)) throw new ProtocolException("JAVA_UNAVAILABLE");
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || Files.size(jar) > 256L * 1024 * 1024)
            throw new ProtocolException("JAR_UNAVAILABLE");
        if (!sha256(jar).equals(artifactSha256)) throw new ProtocolException("JAR_HASH_MISMATCH");
    }
    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] block = new byte[8192]; int count;
                while ((count = input.read(block)) != -1) digest.update(block, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
