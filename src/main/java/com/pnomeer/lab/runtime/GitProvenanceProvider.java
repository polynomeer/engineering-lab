package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.GitProvenance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Bounded, read-only Git probes. Missing Git/repositories remain explicitly unknown. */
public final class GitProvenanceProvider implements Supplier<GitProvenance> {
    private final Path directory;
    private final Duration timeout;
    private static final int OUTPUT_LIMIT = 65536;

    public GitProvenanceProvider(Path directory, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        this.directory = directory;
        this.timeout = timeout;
    }

    @Override
    public GitProvenance get() {
        String commit = null;
        try {
            commit = command("rev-parse", "--verify", "HEAD").strip();
            String status = command("status", "--porcelain=v1", "--untracked-files=normal");
            return new GitProvenance(commit, !status.isBlank(), null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new GitProvenance(commit, null, "Git probe interrupted");
        } catch (Exception ex) {
            return new GitProvenance(commit, null, "Git provenance unavailable: " + ex.getClass().getSimpleName());
        }
    }

    private String command(String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(directory.toAbsolutePath().toString());
        command.addAll(java.util.List.of(arguments));
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        Process process = builder.start();
        var reader = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var output = reader.submit(() -> {
                byte[] bytes = process.getInputStream().readNBytes(OUTPUT_LIMIT + 1);
                if (bytes.length > OUTPUT_LIMIT) process.destroyForcibly();
                return bytes;
            });
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new java.util.concurrent.TimeoutException("Git timeout");
            }
            byte[] bytes = output.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (bytes.length > OUTPUT_LIMIT || process.exitValue() != 0) {
                throw new java.io.IOException("Git output limit or unsuccessful exit");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            process.destroyForcibly();
            process.getInputStream().close();
            reader.shutdownNow();
        }
    }
}
