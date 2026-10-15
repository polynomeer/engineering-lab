package com.pnomeer.lab.app.run;

import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.security.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Downloads a verified private snapshot, never an arbitrary registered filesystem path. */
@RestController
@RequestMapping("/runs")
public final class ArtifactDownloadController {
    private final RunStore store;
    private final Path root;
    private final ArtifactDownloadProperties properties;
    private final Semaphore slots;
    public ArtifactDownloadController(RunStore store, StorageProperties storage, ArtifactDownloadProperties properties) {
        this.store = store; this.root = Path.of(storage.root()); this.properties = properties;
        this.slots = new Semaphore(properties.maxConcurrent());
    }

    @GetMapping("/{runId}/artifacts/{artifactId}/download")
    public void download(@PathVariable String runId, @PathVariable String artifactId, HttpServletResponse response) throws IOException {
        var run = store.find(runId).orElseThrow(() -> error(HttpStatus.NOT_FOUND,"Run not found"));
        if (run.status() == RunStatus.RUNNING || run.status() == RunStatus.QUEUED) {
            throw error(HttpStatus.CONFLICT,"Download requires a terminal Run");
        }
        var reference = store.artifacts(runId).stream().filter(ref -> ref.artifactId().equals(artifactId))
                .findFirst().orElseThrow(() -> error(HttpStatus.NOT_FOUND,"Artifact not found"));
        String directory, extension, mediaType;
        String uuid = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
        if (artifactId.matches("jfr_" + uuid)) {
            directory = "jfr"; extension = ".jfr"; mediaType = "application/octet-stream";
        } else if (artifactId.matches("log_" + uuid)) {
            directory = "logs"; extension = ".ndjson"; mediaType = "application/x-ndjson";
        } else if (artifactId.matches("worker_" + uuid)) {
            directory = "worker-reports"; extension = ".bin"; mediaType = "application/octet-stream";
        } else throw error(HttpStatus.CONFLICT,"Artifact is not a managed capture path");
        String filename = artifactId + extension;
        if (!reference.relativePath().equals(directory + "/" + filename) || !reference.mediaType().equals(mediaType)) {
            throw error(HttpStatus.CONFLICT,"Artifact is not a managed capture path");
        }
        if (reference.byteSize() > properties.maxBytes()) throw error(HttpStatus.PAYLOAD_TOO_LARGE,"Artifact exceeds download limit");
        if (!slots.tryAcquire()) throw error(HttpStatus.TOO_MANY_REQUESTS,"Artifact download capacity reached");
        Path snapshot = null;
        try {
            snapshot = Files.createTempFile("lab-artifact-", ".snapshot");
            verifySnapshot(reference, directory, filename, snapshot);
            response.setContentType(mediaType);
            response.setContentLengthLong(reference.byteSize());
            response.setHeader("Content-Disposition", "attachment; filename=" + filename);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Artifact-SHA256", reference.sha256());
            try (var input = Files.newInputStream(snapshot)) {
                byte[] buffer = new byte[8192];
                int count;
                var output = response.getOutputStream();
                while ((count = input.read(buffer)) != -1) output.write(buffer,0,count);
            }
        } finally {
            try { if (snapshot != null) Files.deleteIfExists(snapshot); }
            finally { slots.release(); }
        }
    }

    private void verifySnapshot(ArtifactReference ref, String directory, String filename, Path snapshot) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        // Relative, no-follow opens prevent directory symlinks and path-swap traversal.
        try (var entries = Files.newDirectoryStream(root)) {
            if (!(entries instanceof SecureDirectoryStream<Path> secured)) {
                throw error(HttpStatus.SERVICE_UNAVAILABLE,"Secure artifact file access is unavailable");
            }
            try (var captures = secured.newDirectoryStream(Path.of(directory),LinkOption.NOFOLLOW_LINKS)) {
                var file = Path.of(filename);
                if (!captures.getFileAttributeView(file,BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)
                        .readAttributes().isRegularFile()) throw error(HttpStatus.CONFLICT,"Artifact is not a regular file");
                try (var channel = captures.newByteChannel(file,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
                     var input = Channels.newInputStream(channel);
                     var output = Files.newOutputStream(snapshot)) {
                    if (channel.size() != ref.byteSize()) throw error(HttpStatus.CONFLICT,"Artifact size mismatch");
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (count > ref.byteSize() - total) throw error(HttpStatus.CONFLICT,"Artifact size changed");
                        output.write(buffer,0,count); digest.update(buffer,0,count); total += count;
                    }
                    if (total != ref.byteSize() || !HexFormat.of().formatHex(digest.digest()).equals(ref.sha256())) {
                        throw error(HttpStatus.CONFLICT,"Artifact checksum or size mismatch");
                    }
                }
            }
        } catch (NoSuchFileException ex) {
            throw error(HttpStatus.NOT_FOUND,"Artifact file is missing");
        } catch (FileSystemException ex) {
            throw error(HttpStatus.CONFLICT,"Artifact path cannot be opened securely");
        }
    }
    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status,message);
    }
}
