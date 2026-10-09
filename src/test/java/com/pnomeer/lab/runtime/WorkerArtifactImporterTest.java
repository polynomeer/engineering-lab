package com.pnomeer.lab.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.core.storage.ArtifactReference;
import com.pnomeer.lab.worker.WireJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkerArtifactImporterTest {
    @TempDir Path root;
    private ObjectNode metadata(String path, byte[] bytes) throws Exception {
        return WireJson.object().put("path", path).put("kind", "REPORT").put("sizeBytes", bytes.length)
                .put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
    private Path workspace() throws Exception { return Files.createDirectory(root.resolve("workspace")); }
    private WorkerArtifactImporter importer(WorkerArtifactImporter.Limits limits) { return new WorkerArtifactImporter(root.resolve("store"), limits); }
    @Test void importsExactBytesAndRetainsIndependentCopyAfterSourceChanges() throws Exception {
        Path workspace = workspace(); Files.createDirectory(workspace.resolve("reports"));
        byte[] original = "한글 report".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(workspace.resolve("reports/result.json"), original);
        List<ArtifactReference> refs = new ArrayList<>();
        importer(WorkerArtifactImporter.Limits.DEFAULT).open("run", refs::add).accept(workspace, metadata("reports/result.json", original));
        assertEquals(1, refs.size()); ArtifactReference ref = refs.getFirst();
        assertEquals("run", ref.runId()); assertEquals(original.length, ref.byteSize());
        Files.writeString(workspace.resolve("reports/result.json"), "changed");
        assertArrayEquals(original, Files.readAllBytes(root.resolve("store").resolve(ref.relativePath())));
    }
    @Test void rejectsHashSizeAndFileLimitsWithoutRegisteringOrLeavingCopies() throws Exception {
        Path workspace = workspace(); Files.writeString(workspace.resolve("report"), "abc");
        for (String fault : List.of("hash", "size", "limit")) {
            var payload = metadata("report", "abc".getBytes());
            if (fault.equals("hash")) payload.put("sha256", "0".repeat(64));
            if (fault.equals("size")) payload.put("sizeBytes", 2);
            var session = importer(new WorkerArtifactImporter.Limits(2, fault.equals("limit") ? 2 : 8, 8)).open("run", ref -> fail("Unexpected registration"));
            assertThrows(java.io.IOException.class, () -> session.accept(workspace, payload));
            assertThrows(java.io.IOException.class, () -> session.accept(workspace, metadata("report", "abc".getBytes())));
        }
        try (var files = Files.list(root.resolve("store/worker-reports"))) { assertEquals(0, files.count()); }
    }
    @Test void rejectsTraversalSymlinksDirectoriesAndUnsupportedKinds() throws Exception {
        Path workspace = workspace(); Path outside = Files.writeString(root.resolve("outside"), "abc");
        Files.createSymbolicLink(workspace.resolve("link"), outside);
        Files.createSymbolicLink(workspace.resolve("linked-directory"), root);
        Files.createDirectory(workspace.resolve("folder"));
        for (String path : List.of("../outside", "/outside", "link", "linked-directory/outside", "folder")) {
            var session = importer(WorkerArtifactImporter.Limits.DEFAULT).open("run", ref -> fail("Unexpected registration"));
            assertThrows(java.io.IOException.class, () -> session.accept(workspace, metadata(path, "abc".getBytes())));
        }
        Files.writeString(workspace.resolve("report"), "abc");
        var jfr = metadata("report", "abc".getBytes()).put("kind", "JFR");
        assertThrows(java.io.IOException.class, () -> importer(WorkerArtifactImporter.Limits.DEFAULT).open("run", ref -> fail()).accept(workspace, jfr));
    }
    @Test void enforcesPerRunCountTotalAndDuplicateLimits() throws Exception {
        Path workspace = workspace(); Files.writeString(workspace.resolve("one"), "abc"); Files.writeString(workspace.resolve("two"), "abc");
        for (String fault : List.of("count", "total", "duplicate")) {
            List<ArtifactReference> refs = new ArrayList<>();
            var session = importer(new WorkerArtifactImporter.Limits(fault.equals("count") ? 1 : 2, 3, fault.equals("total") ? 5 : 6)).open("run", refs::add);
            session.accept(workspace, metadata("one", "abc".getBytes()));
            assertThrows(java.io.IOException.class, () -> session.accept(workspace, metadata(fault.equals("duplicate") ? "one" : "two", "abc".getBytes())));
            assertEquals(1, refs.size());
        }
    }
    @Test void registrationFailureRemovesOnlyTheUnregisteredCopy() throws Exception {
        Path workspace = workspace(); Files.writeString(workspace.resolve("report"), "abc");
        var session = importer(WorkerArtifactImporter.Limits.DEFAULT).open("run", ref -> { throw new IllegalStateException("injected"); });
        assertThrows(IllegalStateException.class, () -> session.accept(workspace, metadata("report", "abc".getBytes())));
        try (var files = Files.list(root.resolve("store/worker-reports"))) { assertEquals(0, files.count()); }
        assertEquals("abc", Files.readString(workspace.resolve("report")));
    }
    @Test void rejectsReplacedWorkspaceAndStorageDirectoryLinks() throws Exception {
        Path workspace = workspace(); Files.writeString(workspace.resolve("report"), "abc");
        Path alias = Files.createSymbolicLink(root.resolve("alias"), workspace);
        assertThrows(java.io.IOException.class, () -> importer(WorkerArtifactImporter.Limits.DEFAULT).open("run", ref -> fail()).accept(alias, metadata("report", "abc".getBytes())));
        Files.delete(root.resolve("store/worker-reports"));
        Files.createSymbolicLink(root.resolve("store/worker-reports"), workspace);
        assertThrows(java.io.IOException.class, () -> importer(WorkerArtifactImporter.Limits.DEFAULT).open("run", ref -> fail()).accept(workspace, metadata("report", "abc".getBytes())));
    }
}
