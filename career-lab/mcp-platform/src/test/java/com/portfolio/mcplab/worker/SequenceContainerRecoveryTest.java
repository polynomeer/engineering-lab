package com.portfolio.mcplab.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SequenceContainerRecoveryTest {
    @TempDir Path root;
    private static final String HASH = "a".repeat(64), ID = "b".repeat(64);
    @Test void exclusiveLeaseAndPersistentIntentPreventActiveOrWrongRunRecovery() throws Exception {
        try (var lease = SequenceContainerRecovery.lock(root)) {
            lease.create("run", HASH, "daemon");
            assertThrows(OverlappingFileLockException.class, () -> SequenceContainerRecovery.lock(root));
            assertThrows(IOException.class, () -> lease.create("run", HASH, "daemon"));
        }
        try (var reopened = SequenceContainerRecovery.lock(root)) {
            assertEquals("run", reopened.read("run", HASH).runId());
            assertThrows(IOException.class, () -> reopened.read("wrong", HASH));
            assertThrows(IOException.class, () -> reopened.read("run", "c".repeat(64)));
        }
    }
    @ParameterizedTest @ValueSource(strings={"partial", "oversize", "symlink"})
    void invalidJournalsRejectBeforeDockerAccess(String mode) throws Exception {
        Path journal = root.resolve(SequenceContainerRecovery.JOURNAL);
        if (mode.equals("symlink")) Files.createSymbolicLink(journal, Files.writeString(root.resolve("target"), "preserve"));
        else Files.writeString(journal, mode.equals("oversize") ? "x".repeat(4097) : "version=1");
        try (var lease = SequenceContainerRecovery.lock(root)) {
            assertThrows(IOException.class, () -> lease.recover("run", HASH, null));
        }
        if (mode.equals("symlink")) assertEquals("preserve", Files.readString(root.resolve("target")));
    }
    @ParameterizedTest @ValueSource(strings={"daemon", "ambiguous", "name", "image", "labels", "id", "unavailable", "not-removed", "daemon-changed"})
    void uncertainOwnershipOrCleanupNeverClaimsRecovery(String mode) throws Exception {
        try (var lease = SequenceContainerRecovery.lock(root)) {
            var intent = lease.create("run", HASH, "daemon");
            var docker = new Fake(intent);
            switch (mode) {
                case "daemon" -> docker.daemon = "another";
                case "ambiguous" -> docker.ids = List.of(ID, "c".repeat(64));
                case "name" -> docker.container = new SequenceContainerRecovery.Container(ID, "/renamed", SequenceContainerRecovery.IMAGE, intent.labels());
                case "image" -> docker.container = new SequenceContainerRecovery.Container(ID, "/"+intent.name(), "other", intent.labels());
                case "labels" -> docker.container = new SequenceContainerRecovery.Container(ID, "/"+intent.name(), SequenceContainerRecovery.IMAGE, Map.of());
                case "id" -> docker.ids = List.of("short-id");
                case "unavailable" -> docker.unavailable = true;
                case "not-removed" -> docker.keep = true;
                case "daemon-changed" -> docker.changeDaemon = true;
            }
            if (mode.equals("unavailable")) assertThrows(IllegalStateException.class, () -> lease.recover("run", HASH, docker));
            else assertThrows(IOException.class, () -> lease.recover("run", HASH, docker));
            assertEquals((mode.equals("not-removed") || mode.equals("daemon-changed")) ? 1 : 0, docker.removals);
            assertTrue(Files.exists(root.resolve(SequenceContainerRecovery.JOURNAL)));
        }
    }
    @Test void recoveryIsIdempotentAndPreservesOwnershipEvidence() throws Exception {
        try (var lease = SequenceContainerRecovery.lock(root)) {
            var intent = lease.create("run", HASH, "daemon");
            byte[] original = Files.readAllBytes(root.resolve(SequenceContainerRecovery.JOURNAL));
            var docker = new Fake(intent);
            assertEquals(1, lease.recover("run", HASH, docker));
            assertEquals(0, lease.recover("run", HASH, docker));
            assertArrayEquals(original, Files.readAllBytes(root.resolve(SequenceContainerRecovery.JOURNAL)));
        }
    }
    private static final class Fake implements SequenceContainerRecovery.Containers {
        String daemon = "daemon";
        List<String> ids = List.of(ID);
        SequenceContainerRecovery.Container container;
        int removals;
        boolean unavailable, keep, changeDaemon;
        int daemonReads;
        Fake(SequenceContainerRecovery.Intent intent) {
            container = new SequenceContainerRecovery.Container(ID, "/"+intent.name(), SequenceContainerRecovery.IMAGE, intent.labels());
        }
        public String daemonId() { if (unavailable) throw new IllegalStateException("Docker unavailable"); return changeDaemon && daemonReads++ > 0 ? "changed" : daemon; }
        public List<String> candidates(String token) { return ids; }
        public SequenceContainerRecovery.Container inspect(String id) { return container; }
        public void remove(String id) { assertEquals(ID, id); removals++; if (!keep) ids = List.of(); }
    }
}
