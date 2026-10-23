package com.pnomeer.lab.app.run;

import com.pnomeer.lab.app.series.RunSeriesService;
import com.pnomeer.lab.app.series.SeriesView;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.StorageProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.zip.*;

/**
 * One zip per decision: series statistics, the standard export of every run, verified captures within a byte
 * budget, an optional Finding draft, a manifest and SHA256SUMS. Built as it streams; nothing is kept on disk
 * except one capture snapshot at a time.
 */
@RestController
@RequestMapping("/evidence-bundles")
public class EvidenceBundleController {
    static final int MAX_SERIES = 2, MAX_RUNS = 120, MAX_DRAFT_BYTES = 256 * 1024;
    private final RunStore store;
    private final RunSeriesService series;
    private final RunEvidenceController exports;
    private final Path root;
    private final long maxArtifactBytes;
    private final Semaphore slots = new Semaphore(2);
    private final JsonMapper json = JsonMapper.builder().build();

    public EvidenceBundleController(RunStore store, RunSeriesService series, RunEvidenceController exports, StorageProperties storage,
            @Value("${lab.bundle.max-artifact-bytes:268435456}") long maxArtifactBytes) {
        if (maxArtifactBytes < 0) throw new IllegalArgumentException("lab.bundle.max-artifact-bytes must be >= 0");
        this.store = store; this.series = series; this.exports = exports;
        this.root = Path.of(storage.root()); this.maxArtifactBytes = maxArtifactBytes;
    }

    @GetMapping(produces = "application/zip")
    public void bundle(@RequestParam(name = "series", required = false) List<String> seriesIds,
            @RequestParam(name = "run", required = false) List<String> runIds, HttpServletResponse response) throws IOException {
        write(seriesIds, runIds, null, response);
    }

    /** Same as GET, with the request body (Markdown) stored as FINDING-DRAFT.md. */
    @PostMapping(consumes = {"text/markdown", "text/plain"}, produces = "application/zip")
    public void bundleWithDraft(@RequestParam(name = "series", required = false) List<String> seriesIds,
            @RequestParam(name = "run", required = false) List<String> runIds, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        byte[] draft = request.getInputStream().readNBytes(MAX_DRAFT_BYTES + 1);
        if (draft.length > MAX_DRAFT_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Finding draft exceeds 256 KiB");
        write(seriesIds, runIds, draft, response);
    }

    private record Member(StoredRun run, String seriesId, Boolean warmup) { }

    private void write(List<String> seriesIds, List<String> runIds, byte[] draft, HttpServletResponse response) throws IOException {
        seriesIds = seriesIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(seriesIds));
        runIds = runIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(runIds));
        if (seriesIds.isEmpty() && runIds.isEmpty()) throw bad("Give at least one series or run");
        if (seriesIds.size() > MAX_SERIES) throw bad("At most " + MAX_SERIES + " series per bundle");
        // Resolve and validate everything before the first byte, so errors are still proper HTTP responses.
        var views = new ArrayList<SeriesView>();
        var members = new LinkedHashMap<String, Member>();
        for (String id : seriesIds) {
            var view = series.view(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown series: " + id));
            if (view.series().status() == RunStatus.QUEUED || view.series().status() == RunStatus.RUNNING)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Series is still running: " + id);
            views.add(view);
            for (var m : view.members()) members.putIfAbsent(m.runId(), new Member(run(m.runId()), id, m.warmup()));
        }
        for (String id : runIds) members.putIfAbsent(id, new Member(run(id), null, null));
        if (members.size() > MAX_RUNS) throw bad("At most " + MAX_RUNS + " runs per bundle");
        for (var m : members.values())
            if (m.run().status() == RunStatus.QUEUED || m.run().status() == RunStatus.RUNNING)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Run is still running: " + m.run().runId());
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Bundle capacity reached");
        try {
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", "attachment; filename=evidence-"
                    + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now()) + ".zip");
            response.setHeader("Cache-Control", "no-store");
            try (var zip = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8)) {
                var files = new ArrayList<Map<String, Object>>();
                var skipped = new ArrayList<Map<String, Object>>();
                for (var view : views) entry(zip, files, "series/" + view.series().seriesId() + ".json", out -> out.write(json.writeValueAsBytes(view)));
                if (views.size() == 2)
                    entry(zip, files, "comparison.json", out -> out.write(json.writeValueAsBytes(
                            series.compare(views.get(0).series().seriesId(), views.get(1).series().seriesId()))));
                else if (views.isEmpty() && members.size() == 2) {
                    var ids = List.copyOf(members.keySet());
                    entry(zip, files, "comparison.json", out -> out.write(json.writeValueAsBytes(exports.compare(ids.get(0), ids.get(1)))));
                }
                long artifactBytes = 0;
                for (var m : members.values()) {
                    var references = store.artifacts(m.run().runId());
                    entry(zip, files, "runs/" + m.run().runId() + ".ndjson", out -> exports.write(m.run(), references, out));
                    for (var reference : references) {
                        var managed = ArtifactDownloadController.managed(reference);
                        String reason = managed.isEmpty() ? "not a managed capture path"
                                : artifactBytes + reference.byteSize() > maxArtifactBytes ? "bundle artifact budget (lab.bundle.max-artifact-bytes) reached" : null;
                        if (reason == null) {
                            try { artifact(zip, files, m.run().runId(), reference, managed.get()); artifactBytes += reference.byteSize(); }
                            catch (ResponseStatusException ex) { reason = ex.getReason(); }
                        }
                        if (reason != null) skipped.add(Map.of("runId", m.run().runId(), "artifactId", reference.artifactId(),
                                "byteSize", reference.byteSize(), "reason", reason));
                    }
                }
                if (draft != null) entry(zip, files, "FINDING-DRAFT.md", out -> out.write(draft));
                var manifest = new LinkedHashMap<String, Object>();
                manifest.put("schemaVersion", 1);
                manifest.put("createdAtEpochMs", System.currentTimeMillis());
                manifest.put("series", seriesIds);
                manifest.put("runs", members.values().stream().map(m -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("runId", m.run().runId()); row.put("experimentType", m.run().experimentType());
                    row.put("scenarioId", m.run().scenarioId()); row.put("status", m.run().status());
                    if (m.seriesId() != null) { row.put("seriesId", m.seriesId()); row.put("warmup", m.warmup()); }
                    return row;
                }).toList());
                manifest.put("files", List.copyOf(files));
                manifest.put("skippedArtifacts", skipped);
                manifest.put("maxArtifactBytes", maxArtifactBytes);
                entry(zip, files, "manifest.json", out -> out.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest)));
                var sums = new StringBuilder();
                for (var file : files) sums.append(file.get("sha256")).append("  ").append(file.get("path")).append('\n');
                zip.putNextEntry(new ZipEntry("SHA256SUMS"));
                zip.write(sums.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } finally {
            slots.release();
        }
    }

    private void artifact(ZipOutputStream zip, List<Map<String, Object>> files, String runId, ArtifactReference reference,
            ArtifactDownloadController.Managed managed) throws IOException {
        Path snapshot = Files.createTempFile("lab-bundle-", ".snapshot");
        try {
            ArtifactDownloadController.verifySnapshot(root, reference, managed.directory(), managed.filename(), snapshot);
            entry(zip, files, "artifacts/" + runId + "/" + managed.filename(), out -> Files.copy(snapshot, out));
        } finally {
            Files.deleteIfExists(snapshot);
        }
    }

    @FunctionalInterface
    private interface Body { void write(OutputStream out) throws IOException; }

    private static void entry(ZipOutputStream zip, List<Map<String, Object>> files, String path, Body body) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        zip.putNextEntry(new ZipEntry(path));
        long[] bytes = {0};
        var counting = new FilterOutputStream(zip) {
            @Override public void write(int b) throws IOException { zip.write(b); digest.update((byte) b); bytes[0]++; }
            @Override public void write(byte[] b, int off, int len) throws IOException { zip.write(b, off, len); digest.update(b, off, len); bytes[0] += len; }
            @Override public void close() { /* the zip stays open */ }
        };
        body.write(counting);
        zip.closeEntry();
        files.add(Map.of("path", path, "bytes", bytes[0], "sha256", HexFormat.of().formatHex(digest.digest())));
    }

    private StoredRun run(String id) {
        return store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run: " + id));
    }

    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
