package com.pnomeer.lab.runtime;

import com.pnomeer.lab.worker.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ContainerWorkerResourcesTest {
    @TempDir Path root;
    private void failPreparation(boolean failCleanup, AtomicBoolean cleanup) throws Exception {
        var command = WireJson.object().put("protocolVersion",1).put("runId","run").put("experimentId","fixture")
                .put("definitionSha256","a".repeat(64)).put("deadlineEpochMs",System.currentTimeMillis()+60000).put("seed",1).put("workspace",root.toString());
        command.set("parameters",WireJson.object());
        var manifest = new ContainerWorkerManifest("sha256:"+"b".repeat(64),"c".repeat(64),"module",Set.of("fixture"));
        new ContainerWorkerProcess(manifest, WorkerProcess.Limits.DEFAULT, (request, workspace, artifact) -> new ContainerWorkerResources.Session() {
            public void prepare() throws Exception { throw new IOException("DB preparation failed"); }
            public String networkId() { throw new AssertionError("Must not launch worker after preparation failure"); }
            public Map<String,String> environment() { throw new AssertionError(); }
            public void finish(boolean workerRemoved) throws Exception {
                assertTrue(workerRemoved); cleanup.set(true);
                if (failCleanup) throw new IOException("Resource cleanup failed");
            }
        }).execute(command,root,new WorkerProcess.EvidenceSink() {
            public void persist(WorkerProtocol.Event event,Path workspace) { fail("No worker evidence before resources are ready"); }
            public void flush() { fail("No worker flush before resources are ready"); }
        });
    }
    @Test void preparationFailureStillReclaimsResourcesBeforeAnyWorkerLaunch() {
        var cleanup = new AtomicBoolean();
        assertThrows(IOException.class,()->failPreparation(false,cleanup)); assertTrue(cleanup.get());
    }
    @Test void cleanupUncertaintyPreservesWorkspaceAndOverridesPreparationFailure() {
        var cleanup = new AtomicBoolean();
        var failure=assertThrows(WorkerProcess.Failure.class,()->failPreparation(true,cleanup));
        assertEquals("RESOURCE_CLEANUP_UNCONFIRMED",failure.getMessage());assertTrue(cleanup.get());assertNotNull(failure.workspace());
    }
}
