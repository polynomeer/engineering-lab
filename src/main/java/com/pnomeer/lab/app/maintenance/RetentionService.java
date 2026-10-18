package com.pnomeer.lab.app.maintenance;

import com.pnomeer.lab.core.storage.ArtifactReference;
import com.pnomeer.lab.storage.JdbcRunStore;
import com.pnomeer.lab.storage.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** Previews and prunes old JFR/Run-log captures. The token binds an apply to the exact previewed list. */
@Service
@EnableConfigurationProperties(RetentionProperties.class)
public class RetentionService {
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String UUID = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    static final int MAX_CANDIDATES = 1000;
    private final JdbcRunStore store;
    private final Path root;
    private final RetentionProperties properties;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public RetentionService(JdbcRunStore store, StorageProperties storage, RetentionProperties properties) {
        this(store, Path.of(storage.root()), properties, Clock.systemUTC());
    }
    RetentionService(JdbcRunStore store, Path root, RetentionProperties properties, Clock clock) {
        this.store = store; this.root = root; this.properties = properties; this.clock = clock;
    }

    public record Candidate(String runId, String artifactId, String relativePath, long byteSize) { }
    public record Preview(int olderThanDays, long cutoffEpochMs, List<Candidate> candidates, long totalBytes, boolean truncated,
            int baselineProtectedRuns, boolean applyEnabled, String token) { }
    public record Result(int deletedReferences, long deletedBytes, List<String> missingFiles) { }

    public Preview preview(int olderThanDays) {
        if (olderThanDays < 1 || olderThanDays > 3650) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "olderThanDays must be 1..3650");
        // Day-aligned cutoff: the same preview and apply on one day select the same runs.
        long day = 86_400_000L;
        long cutoff = (clock.millis() / day) * day - (long) olderThanDays * day;
        var references = store.retentionCandidates(cutoff, MAX_CANDIDATES);
        var candidates = references.stream().filter(RetentionService::managed)
                .map(r -> new Candidate(r.runId(), r.artifactId(), r.relativePath(), r.byteSize())).toList();
        long bytes = candidates.stream().mapToLong(Candidate::byteSize).sum();
        return new Preview(olderThanDays, cutoff, candidates, bytes, references.size() == MAX_CANDIDATES,
                store.baselineProtectedRuns(), properties.applyEnabled(), token(cutoff, candidates));
    }

    public Result apply(int olderThanDays, String token) {
        if (!properties.applyEnabled()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Set lab.retention.apply-enabled=true to delete captures");
        var preview = preview(olderThanDays);
        if (!preview.token().equals(token)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Candidates changed since the preview; preview again");
        int deleted = 0; long bytes = 0;
        var missing = new ArrayList<String>();
        for (var candidate : preview.candidates()) {
            // Reference first: a failure can leave an orphan file, never a dangling reference.
            store.deleteArtifactReference(candidate.artifactId());
            deleted++; bytes += candidate.byteSize();
            if (!deleteFile(candidate)) missing.add(candidate.relativePath());
            record(candidate);
        }
        log.info("stage=retention deleted={} bytes={} missing={}", deleted, bytes, missing.size());
        return new Result(deleted, bytes, missing);
    }

    private static boolean managed(ArtifactReference ref) {
        return ref.artifactId().matches("jfr_" + UUID) && ref.relativePath().equals("jfr/" + ref.artifactId() + ".jfr")
                || ref.artifactId().matches("log_" + UUID) && ref.relativePath().equals("logs/" + ref.artifactId() + ".ndjson");
    }

    private boolean deleteFile(Candidate candidate) {
        String directory = candidate.relativePath().substring(0, candidate.relativePath().indexOf('/'));
        String filename = candidate.relativePath().substring(directory.length() + 1);
        // No-follow relative deletion keeps a symlinked directory from redirecting the delete.
        try (var entries = Files.newDirectoryStream(root)) {
            if (!(entries instanceof SecureDirectoryStream<Path> secured)) throw new IOException("Secure file access unavailable");
            try (var captures = secured.newDirectoryStream(Path.of(directory), LinkOption.NOFOLLOW_LINKS)) {
                captures.deleteFile(Path.of(filename));
                return true;
            }
        } catch (NoSuchFileException ex) {
            return false;
        } catch (IOException ex) {
            log.warn("stage=retention artifact_id={} reason=file not deleted: {}", candidate.artifactId(), ex.getMessage());
            return false;
        }
    }

    private void record(Candidate candidate) {
        var line = new LinkedHashMap<String, Object>();
        line.put("deletedAtEpochMs", clock.millis());
        line.put("runId", candidate.runId());
        line.put("artifactId", candidate.artifactId());
        line.put("relativePath", candidate.relativePath());
        line.put("byteSize", candidate.byteSize());
        try {
            Files.writeString(root.resolve("retention-log.ndjson"), JSON.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.warn("stage=retention artifact_id={} reason=cannot append retention log: {}", candidate.artifactId(), ex.getMessage());
        }
    }

    static String token(long cutoff, List<Candidate> candidates) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(cutoff).getBytes(StandardCharsets.UTF_8));
            for (var c : candidates) digest.update(("\n" + c.artifactId()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
