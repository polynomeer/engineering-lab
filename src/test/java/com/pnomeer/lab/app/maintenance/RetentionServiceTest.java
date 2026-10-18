package com.pnomeer.lab.app.maintenance;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.JdbcRunStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetentionServiceTest {
    @TempDir Path root;
    private final Clock later = Clock.offset(Clock.systemUTC(), Duration.ofDays(40));

    private String finished(JdbcRunStore store, String id) {
        store.create("queue", "small", new RunMetadata(id, Map.of(), RunEnvironment.capture(), new GitProvenance("abc", false, null)));
        store.start(id);
        store.complete(id, RunStatus.SUCCEEDED, ExperimentSummary.empty(), null);
        return id;
    }
    private String capture(JdbcRunStore store, String runId, String kind) throws Exception {
        String id = kind + "_" + UUID.randomUUID();
        String directory = kind.equals("jfr") ? "jfr" : kind.equals("log") ? "logs" : "worker-reports";
        String file = id + (kind.equals("jfr") ? ".jfr" : kind.equals("log") ? ".ndjson" : ".bin");
        Files.createDirectories(root.resolve(directory));
        Files.writeString(root.resolve(directory).resolve(file), "x".repeat(10));
        store.registerArtifact(new ArtifactReference(id, runId, directory + "/" + file,
                kind.equals("log") ? "application/x-ndjson" : "application/octet-stream", 10, "a".repeat(64)));
        return id;
    }

    @Test void previewsOnlyOldCapturesOutsideBaselinesAndAppliesOnlyWithTheMatchingToken() throws Exception {
        try (var store = new JdbcRunStore(root, 10, 2)) {
            String old = finished(store, "old");
            String jfr = capture(store, old, "jfr"), logs = capture(store, old, "log"), report = capture(store, old, "worker");
            store.create("queue", "small", new RunMetadata("active", Map.of(), RunEnvironment.capture(), new GitProvenance("abc", false, null)));
            capture(store, "active", "jfr");
            String kept = finished(store, "kept");
            capture(store, kept, "jfr");
            store.createSeries("base", "queue", "small", "a".repeat(64), 1, 0);
            store.startSeries("base");
            store.addSeriesRun("base", new RunSeries.Member(0, kept, false));
            store.completeSeries("base", RunStatus.SUCCEEDED, null);
            store.pinBaseline("base");

            var disabled = new RetentionService(store, root, new RetentionProperties(false), later);
            var preview = disabled.preview(30);
            assertEquals(Set.of(jfr, logs), preview.candidates().stream().map(RetentionService.Candidate::artifactId).collect(java.util.stream.Collectors.toSet()));
            assertEquals(20, preview.totalBytes());
            assertEquals(1, preview.baselineProtectedRuns());
            assertFalse(preview.applyEnabled());
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> disabled.apply(30, preview.token())).getStatusCode().value());
            assertTrue(new RetentionService(store, root, new RetentionProperties(false), Clock.systemUTC()).preview(30).candidates().isEmpty());

            var enabled = new RetentionService(store, root, new RetentionProperties(true), later);
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> enabled.apply(30, "stale")).getStatusCode().value());
            var result = enabled.apply(30, preview.token());
            assertEquals(2, result.deletedReferences());
            assertEquals(20, result.deletedBytes());
            assertTrue(result.missingFiles().isEmpty());
            assertEquals(List.of(report), store.artifacts(old).stream().map(ArtifactReference::artifactId).toList());
            assertFalse(Files.exists(root.resolve("jfr").resolve(jfr + ".jfr")));
            assertTrue(Files.exists(root.resolve("worker-reports").resolve(report + ".bin")));
            assertEquals(1, store.artifacts(kept).size()); // baseline run untouched
            assertEquals(2, Files.readAllLines(root.resolve("retention-log.ndjson")).size());
            assertTrue(enabled.preview(30).candidates().isEmpty());
            assertThrows(ResponseStatusException.class, () -> enabled.preview(0));
        }
    }
}
