package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.worker.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerStartupRecoveryTest {
    @TempDir Path root;
    private static final String ID = "career.mcp-platform.sequence";
    private WorkerStartupRecovery recovery(WorkerStartupRecovery.Reconciler reconciler) {
        return new WorkerStartupRecovery(reconciler, outcome ->
                outcome.status().equals("SUCCEEDED") && outcome.reason().isEmpty()
                || outcome.status().equals("FAILED") && outcome.reason().equals("EXPECTATION_NOT_MET"));
    }
    private LocalJvmRegistration binding(String hash) {
        return new LocalJvmRegistration("sequence-worker", new WorkerManifest(
                Path.of(System.getProperty("java.home"), "bin", "java"), root.resolve("worker.jar"), hash,
                "mcp-platform", Set.of(ID), 64), root, root, Set.of(), ignored -> null,
                () -> new LocalJvmRegistration.Readiness("READY", null), List.of());
    }
    private WorkerRecoveryGuard guard() { return new WorkerRecoveryGuard(LocalJvmRegistration.marker(root, "sequence-worker")); }
    private RunStore store(long completed) {
        var store = mock(RunStore.class);
        when(store.find("run")).thenReturn(Optional.of(new StoredRun(0, "run", ID, "scenario", RunStatus.FAILED,
                1, 1, completed, null, null, "preserve failure")));
        return store;
    }
    private void receipt(String status, String reason) throws Exception {
        guard().begin("run", "a".repeat(64)).recordCompletion(Files.createDirectory(root.resolve("worker-one")), status, reason);
    }
    @Test void completedExpectationFailureReconcilesOnceAndRestoresAdmissionWithoutRewritingRun() throws Exception {
        receipt("FAILED", "EXPECTATION_NOT_MET");
        var calls = new AtomicInteger();
        var recovery = recovery((manifest, workspace, run) -> {
            assertEquals(root.resolve("worker-one"), workspace); assertEquals("run", run);
            calls.incrementAndGet(); return true;
        });
        var store = store(2);
        assertEquals(WorkerStartupRecovery.Result.RECOVERED, recovery.recover(binding("a".repeat(64)), store));
        assertFalse(guard().pending());
        assertTrue(binding("a".repeat(64)).readiness().get().ready());
        assertEquals(WorkerStartupRecovery.Result.NOT_REQUIRED, recovery.recover(binding("a".repeat(64)), store));
        assertEquals(1, calls.get());
        verify(store).find("run"); verifyNoMoreInteractions(store);
    }
    @Test void successfulTransportReceiptSurvivesReopenBeforeAdmissionRelease() throws Exception {
        receipt("SUCCEEDED", "");
        assertEquals(WorkerStartupRecovery.Result.RECOVERED,
                recovery((m,w,r) -> true).recover(binding("a".repeat(64)), store(2)));
    }
    @Test void missingReceiptChangedDeploymentAndActiveRunNeverInvokeRecovery() throws Exception {
        var ticket = guard().begin("run", "a".repeat(64));
        var recovery = recovery((m,w,r) -> { fail("Unsafe recovery invoked"); return true; });
        assertEquals(WorkerStartupRecovery.Result.REQUIRES_OPERATOR, recovery.recover(binding("a".repeat(64)), store(2)));
        ticket.recordCompletion(Files.createDirectory(root.resolve("worker-one")), "SUCCEEDED", "");
        assertEquals(WorkerStartupRecovery.Result.REQUIRES_OPERATOR, recovery.recover(binding("b".repeat(64)), store(2)));
        assertEquals(WorkerStartupRecovery.Result.REQUIRES_OPERATOR, recovery.recover(binding("a".repeat(64)), store(0)));
        assertTrue(guard().pending());
    }
    @ParameterizedTest @ValueSource(strings={"EXECUTION_OR_PREPARATION_FAILED", "CONTAINER_CLEANUP_FAILED", "PROTOCOL_FAILED", ""})
    void uncertainFailureDoesNotInferCleanupFromResult(String reason) throws Exception {
        receipt("FAILED", reason);
        assertEquals(WorkerStartupRecovery.Result.REQUIRES_OPERATOR,
                recovery((m,w,r) -> { fail("Unsafe recovery"); return true; })
                        .recover(binding("a".repeat(64)), store(2)));
        assertTrue(guard().pending());
    }
    @Test void failedOrInterruptedRecoveryRetainsBothFilesAndInterruptFlag() throws Exception {
        receipt("SUCCEEDED", "");
        Path marker = LocalJvmRegistration.marker(root, "sequence-worker");
        byte[] original = Files.readAllBytes(marker);
        assertEquals(WorkerStartupRecovery.Result.FAILED, recovery((m,w,r) -> false)
                .recover(binding("a".repeat(64)), store(2)));
        try {
            assertEquals(WorkerStartupRecovery.Result.FAILED, recovery((m,w,r) -> { throw new InterruptedException(); })
                    .recover(binding("a".repeat(64)), store(2)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertArrayEquals(original, Files.readAllBytes(marker));
        assertNotNull(guard().completed("a".repeat(64)));
    }
    @Test void replacementMarkerCannotBeReleasedAfterReconciliation() throws Exception {
        receipt("SUCCEEDED", "");
        assertEquals(WorkerStartupRecovery.Result.FAILED, recovery((m,w,r) -> {
            Files.writeString(LocalJvmRegistration.marker(root, "sequence-worker"), "replacement"); return true;
        }).recover(binding("a".repeat(64)), store(2)));
        assertTrue(guard().pending());
    }
    @ParameterizedTest @ValueSource(strings={"partial", "oversize", "symlink", "workspace-link", "wrong-owner", "traversal"})
    void invalidReceiptNeverInvokesRecovery(String mode) throws Exception {
        receipt("SUCCEEDED", "");
        Path file = root.resolve(".active-sequence-worker.properties.completed");
        switch (mode) {
            case "partial" -> Files.writeString(file, "version=1\n");
            case "oversize" -> Files.writeString(file, "x".repeat(4097));
            case "symlink" -> { Files.move(file, root.resolve("original")); Files.createSymbolicLink(file, root.resolve("original")); }
            case "workspace-link" -> { Files.delete(root.resolve("worker-one")); Files.createSymbolicLink(root.resolve("worker-one"), root); }
            case "wrong-owner" -> Files.writeString(file, Files.readString(file).replaceAll("markerSha256=.*", "markerSha256=bad"));
            case "traversal" -> Files.writeString(file, Files.readString(file).replace("workspace=worker-one", "workspace=../outside"));
        }
        assertEquals(WorkerStartupRecovery.Result.REQUIRES_OPERATOR, recovery((m,w,r) -> {
            fail("Invalid receipt used"); return true;
        }).recover(binding("a".repeat(64)), store(2)));
        assertTrue(guard().pending());
    }
    @ParameterizedTest @ValueSource(strings={"success", "nonzero", "wrong-run", "oversize", "malformed", "hang", "admission", "removed"})
    void recoveryProcessRequiresBoundedExactResponseAndZeroExit(String mode) throws Exception {
        Path jar = root.resolve("worker.jar");
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", RecoveryFixture.class.getName());
        String entry = RecoveryFixture.class.getName().replace('.', '/') + ".class";
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest);
             var input = RecoveryFixture.class.getResourceAsStream("/" + entry)) {
            output.putNextEntry(new java.util.jar.JarEntry(entry)); input.transferTo(output); output.closeEntry();
        }
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        Path workspace = Files.createDirectory(root.resolve("worker-" + mode));
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertEquals(mode.equals("success"),
                WorkerStartupRecovery.recoverProcess(binding(hash).manifest(), workspace, "run", Duration.ofSeconds(2))));
        if (mode.equals("hang")) {
            long pid = Long.parseLong(Files.readString(workspace.resolve("pid")));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }
    public static class RecoveryFixture {
        public static void main(String[] args) throws Exception {
            Path workspace = Path.of(args[1]); String mode = workspace.getFileName().toString().substring(7);
            if (!args[0].equals("--recover")) System.exit(9);
            if (mode.equals("hang")) { Files.writeString(workspace.resolve("pid"), Long.toString(ProcessHandle.current().pid())); Thread.sleep(60000); }
            if (mode.equals("oversize")) { System.out.print("x".repeat(8192)); return; }
            if (mode.equals("malformed")) { System.out.print("{broken"); return; }
            System.out.print("{\"status\":\"RECOVERED\",\"runId\":\"" + (mode.equals("wrong-run") ? "wrong" : args[2])
                    + "\",\"removedContainers\":" + (mode.equals("removed") ? "2" : "0")
                    + ",\"admissionReleased\":" + mode.equals("admission") + "}");
            if (mode.equals("nonzero")) System.exit(7);
        }
    }
}
