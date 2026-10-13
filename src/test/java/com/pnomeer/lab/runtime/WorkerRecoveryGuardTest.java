package com.pnomeer.lab.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class WorkerRecoveryGuardTest {
    @TempDir Path root;
    @Test void exclusiveMarkerSurvivesReopenAndOnlyItsOwnerCanReleaseIt() throws Exception {
        Path marker = root.resolve("active.properties");
        var guard = new WorkerRecoveryGuard(marker);
        var ticket = guard.begin("run-one", "a".repeat(64));
        assertTrue(new WorkerRecoveryGuard(marker).pending());
        assertThrows(java.io.IOException.class, () -> new WorkerRecoveryGuard(marker).begin("run-two", "b".repeat(64)));
        assertTrue(Files.readString(marker).contains("runId=run-one"));
        ticket.confirmSuccess();
        assertFalse(guard.pending());
        guard.begin("run-two", "b".repeat(64));
        assertThrows(java.io.IOException.class, ticket::confirmSuccess);
        assertTrue(guard.pending());
    }
    @Test void partialOversizeAndSymlinkMarkersFailClosed() throws Exception {
        Path marker = root.resolve("active.properties");
        var guard = new WorkerRecoveryGuard(marker);
        Files.createFile(marker);
        assertTrue(guard.pending());
        assertThrows(java.io.IOException.class, () -> guard.begin("run", "a".repeat(64)));
        Files.delete(marker);
        var ticket = guard.begin("run", "a".repeat(64));
        Files.writeString(marker, "x".repeat(4097));
        assertThrows(java.io.IOException.class, ticket::confirmSuccess);
        Files.delete(marker);
        Path target = root.resolve("operator.txt"); Files.writeString(target, "preserve");
        Files.createSymbolicLink(marker, target);
        assertTrue(guard.pending());
        assertThrows(java.io.IOException.class, () -> guard.begin("run", "a".repeat(64)));
        assertThrows(java.io.IOException.class, ticket::confirmSuccess);
        assertEquals("preserve", Files.readString(target));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false, true})
    void killedControllerLeavesDurableMarkerForNextProcess(boolean completed) throws Exception {
        Path marker = root.resolve("active.properties");
        String classpath = Path.of(CrashFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + java.io.File.pathSeparator + Path.of(WorkerRecoveryGuard.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, CrashFixture.class.getName(), marker.toString(), Boolean.toString(completed)).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertEquals(1, child.getInputStream().read()));
            child.destroyForcibly();
            assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            var restarted = new WorkerRecoveryGuard(marker);
            assertTrue(restarted.pending());
            assertThrows(java.io.IOException.class, () -> restarted.begin("new-run", "a".repeat(64)));
            assertTrue(Files.readString(marker).contains("runId=crashed-run"));
            if (completed) assertEquals("SUCCEEDED", restarted.completed("a".repeat(64)).status());
            else assertThrows(java.io.IOException.class, () -> restarted.completed("a".repeat(64)));
        } finally { child.destroyForcibly(); child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS); }
    }
    @Test void orphanReceiptBlocksReadinessAndNewTicket() throws Exception {
        Path marker = root.resolve("active.properties");
        var guard = new WorkerRecoveryGuard(marker);
        guard.begin("run", "a".repeat(64)).recordCompletion(Files.createDirectory(root.resolve("worker-one")), "SUCCEEDED", "");
        Files.delete(marker);
        assertTrue(guard.pending());
        assertThrows(java.io.IOException.class, () -> guard.begin("next", "a".repeat(64)));
    }
    public static class CrashFixture {
        public static void main(String[] args) throws Exception {
            Path marker = Path.of(args[0]);
            var ticket = new WorkerRecoveryGuard(marker).begin("crashed-run", "a".repeat(64));
            if (Boolean.parseBoolean(args[1])) ticket.recordCompletion(
                    Files.createDirectory(marker.getParent().resolve("worker-crashed")), "SUCCEEDED", "");
            System.out.write(1); System.out.flush();
            Thread.sleep(60000);
        }
    }
}
