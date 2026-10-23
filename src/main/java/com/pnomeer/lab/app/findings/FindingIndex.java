package com.pnomeer.lab.app.findings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Run IDs cited by Finding documents ({@code lab.findings.directory}, top-level *.md except README and
 * TEMPLATE). Rescanned at most every 30 seconds; a missing directory simply means no Findings.
 */
@Component
public class FindingIndex {
    private static final Logger log = LoggerFactory.getLogger(FindingIndex.class);
    private static final Pattern RUN_ID = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,150}\\.md");
    static final int MAX_FILES = 500, MAX_BYTES = 1 << 20;
    private static final long RESCAN_MS = 30_000;
    private final Path directory;
    private List<Finding> findings = List.of();
    private long scannedAt = Long.MIN_VALUE;

    public record Finding(String file, String title, Set<String> runIds) {
        public Finding { runIds = Set.copyOf(runIds); }
    }

    public FindingIndex(@Value("${lab.findings.directory:docs/findings}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    public static FindingIndex empty() { return new FindingIndex("/nonexistent-findings-" + UUID.randomUUID()); }

    public synchronized List<Finding> findings() {
        long now = System.currentTimeMillis();
        if (now - scannedAt >= RESCAN_MS || scannedAt == Long.MIN_VALUE) { findings = scan(); scannedAt = now; }
        return findings;
    }

    public List<Finding> citing(String runId) {
        return findings().stream().filter(f -> f.runIds().contains(runId)).toList();
    }

    public Set<String> citedRunIds() {
        var ids = new HashSet<String>();
        findings().forEach(f -> ids.addAll(f.runIds()));
        return ids;
    }

    /** The raw Markdown of one indexed Finding, never a path outside the directory. */
    public Optional<String> read(String file) {
        if (!FILE.matcher(file).matches() || findings().stream().noneMatch(f -> f.file().equals(file))) return Optional.empty();
        try { return Optional.of(Files.readString(directory.resolve(file), StandardCharsets.UTF_8)); }
        catch (IOException ex) { return Optional.empty(); }
    }

    private List<Finding> scan() {
        if (!Files.isDirectory(directory)) return List.of();
        var result = new ArrayList<Finding>();
        try (var files = Files.newDirectoryStream(directory, "*.md")) {
            for (Path path : files) {
                String name = path.getFileName().toString();
                if (name.equals("README.md") || name.equals("TEMPLATE.md") || !FILE.matcher(name).matches()) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) continue;
                if (result.size() >= MAX_FILES) { log.warn("stage=findings reason=more than {} files; rest ignored", MAX_FILES); break; }
                String text = Files.readString(path, StandardCharsets.UTF_8);
                var ids = new TreeSet<String>();
                RUN_ID.matcher(text).results().forEach(m -> ids.add(m.group()));
                String title = text.lines().filter(l -> l.startsWith("# ")).findFirst().map(l -> l.substring(2).strip()).orElse(name);
                result.add(new Finding(name, title.length() > 200 ? title.substring(0, 200) : title, ids));
            }
        } catch (IOException | java.io.UncheckedIOException ex) {
            log.warn("stage=findings reason=cannot scan {}: {}", directory, ex.getMessage());
            return findings;
        }
        result.sort(Comparator.comparing(Finding::file).reversed());
        return List.copyOf(result);
    }
}
