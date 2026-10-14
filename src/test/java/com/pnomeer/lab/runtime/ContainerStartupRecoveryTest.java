package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.worker.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContainerStartupRecoveryTest {
    @TempDir Path root;
    final ContainerWorkerManifest manifest = new ContainerWorkerManifest("sha256:" + "b".repeat(64), "a".repeat(64), "module", Set.of("experiment"));
    ContainerRecoveryGuard guard() { return new ContainerRecoveryGuard(root, "registration"); }
    ContainerWorkerRegistration binding(ContainerWorkerResources resources) {
        return new ContainerWorkerRegistration("registration", manifest, root, root, Set.of(), ignored -> null, resources,
                () -> new LocalJvmRegistration.Readiness("READY", null), List.of());
    }
    RunStore store() {
        var store = mock(RunStore.class);
        when(store.find("run")).thenReturn(Optional.of(new StoredRun(1,"run","experiment","scenario",RunStatus.INTERRUPTED,1,2,3,null,ExperimentSummary.empty(),"preserve")));
        when(store.execution("run")).thenReturn(Optional.of(new RunExecution(new RunExecution.Definition(RunExecution.Kind.CONTAINER,"module",1,RunExecution.MeasurementScope.WORKER_PROCESS),null,RunExecution.Phase.PREPARING,false)));
        return store;
    }
    Path begin() throws Exception {
        try (var ticket = guard().acquire()) { return ticket.begin(WireJson.object().put("runId","run").put("experimentId","experiment"), manifest, "none-v1"); }
    }
    @Test void preMutationCrashCanReleaseWithoutDockerAndDoesNotRewriteRun() throws Exception {
        Path workspace = begin(); Files.delete(workspace);
        var store = store();
        assertEquals(WorkerStartupRecovery.Result.RECOVERED, binding(ContainerWorkerResources.NONE).recover(store, n -> { throw new AssertionError(); }));
        assertFalse(guard().pending());
        verify(store, never()).complete(anyString(),any(),any(),any());
    }
    @ParameterizedTest @ValueSource(strings={"partial","oversize","link","workspace-link","traversal","deployment","version"})
    void invalidMarkerBlocksBeforeResourceAccess(String fault) throws Exception {
        Path workspace = begin(), marker = ContainerRecoveryGuard.marker(root,"registration");
        switch(fault) {
            case "partial" -> Files.writeString(marker,"{");
            case "oversize" -> Files.writeString(marker,"x".repeat(4097));
            case "link" -> { Files.move(marker,root.resolve("saved")); Files.createSymbolicLink(marker,root.resolve("saved")); }
            case "workspace-link" -> { Files.delete(workspace); Files.createSymbolicLink(workspace,root); }
            default -> {
                var value = ContainerJournal.json(marker);
                if (fault.equals("traversal")) value.put("workspace","../outside");
                if (fault.equals("deployment")) value.put("artifactSha256","c".repeat(64));
                if (fault.equals("version")) value.put("version",1);
                Files.write(marker,WireJson.encode(value));
            }
        }
        assertEquals(WorkerStartupRecovery.Result.REQUIRES_OPERATOR, binding(ContainerWorkerResources.NONE).recover(store(), n -> { throw new AssertionError(); }));
        assertTrue(guard().pending());
    }
    @ParameterizedTest @ValueSource(strings={"create","start","engine","delete","lost-delete"})
    void faultsKeepAdmissionAndConfirmedDeleteCanResume(String fault) throws Exception {
        Path workspace = begin(), journal = Files.createDirectory(workspace.resolve("container-lifecycle"));
        var spec = new WorkerContainerLifecycle.Spec("run",manifest.artifactSha256(),manifest.imageId(),List.of("sleep","900"));
        var engine = new Fake();
        try (var lifecycle = new WorkerContainerLifecycle(journal,spec,engine)) {
            if (fault.equals("create")) engine.failure = fault;
            try { lifecycle.create(); if (fault.equals("start")) engine.failure = fault; lifecycle.start(); }
            catch (IOException expected) { assertTrue(Set.of("create","start").contains(fault)); }
        }
        engine.failure = fault;
        byte[] marker = Files.readAllBytes(ContainerRecoveryGuard.marker(root,"registration"));
        assertEquals(WorkerStartupRecovery.Result.REQUIRES_OPERATOR,binding(ContainerWorkerResources.NONE).recover(store(), n -> engine));
        assertArrayEquals(marker,Files.readAllBytes(ContainerRecoveryGuard.marker(root,"registration")));
        int removes = engine.removes;
        engine.failure = "";
        var result = binding(ContainerWorkerResources.NONE).recover(store(), n -> engine);
        if (Set.of("create","start").contains(fault)) assertEquals(WorkerStartupRecovery.Result.REQUIRES_OPERATOR,result);
        else { assertEquals(WorkerStartupRecovery.Result.RECOVERED,result); assertFalse(guard().pending()); }
        if (fault.equals("lost-delete")) assertEquals(removes,engine.removes);
    }
    @Test void lockActiveRunAndLegacyMarkerPreventReleaseOrCrossModeAdmission() throws Exception {
        begin();
        try (var ticket = guard().acquire()) {
            assertEquals(WorkerStartupRecovery.Result.REQUIRES_OPERATOR,binding(ContainerWorkerResources.NONE).recover(store()));
        }
        var store = store(); when(store.find("run")).thenReturn(Optional.empty());
        assertEquals(WorkerStartupRecovery.Result.REQUIRES_OPERATOR,binding(ContainerWorkerResources.NONE).recover(store));
        assertEquals(WorkerStartupRecovery.Result.RECOVERED,binding(ContainerWorkerResources.NONE).recover(store()));
        Path legacy = LocalJvmRegistration.marker(root,"registration");
        new WorkerRecoveryGuard(legacy).begin("legacy-run",manifest.artifactSha256());
        byte[] bytes = Files.readAllBytes(legacy);
        assertFalse(binding(ContainerWorkerResources.NONE).readiness().get().ready());
        assertThrows(IOException.class,this::begin);
        assertEquals(WorkerStartupRecovery.Result.NOT_REQUIRED,binding(ContainerWorkerResources.NONE).recover(store()));
        assertArrayEquals(bytes,Files.readAllBytes(legacy));
    }
    @Test void storedWorkerFromAnotherDeploymentCannotReleaseAdmission() throws Exception {
        begin(); var store = store();
        var definition = new RunExecution.Definition(RunExecution.Kind.CONTAINER,"module",1,RunExecution.MeasurementScope.WORKER_PROCESS);
        var worker = new RunExecution.Worker(RunEnvironment.capture(),GitProvenance.unknown("fixture"),"f".repeat(64));
        when(store.execution("run")).thenReturn(Optional.of(new RunExecution(definition,worker,RunExecution.Phase.PREPARING,false)));
        assertEquals(WorkerStartupRecovery.Result.REQUIRES_OPERATOR,binding(ContainerWorkerResources.NONE).recover(store));
        assertTrue(guard().pending());
    }
    @Test void resourcesMustFinishBeforeReleaseAndReplacementMarkerIsNeverDeleted() throws Exception {
        begin(); AtomicInteger calls = new AtomicInteger();
        var resources = new ContainerWorkerResources() {
            public String profile() { return "none-v1"; }
            public Session open(ObjectNode c,Path p,String h) { throw new AssertionError(); }
            public Recovery recovery(Path p,String r,String h) { return new Recovery() {
                public String networkId() { return "none"; }
                public void finish() throws Exception {
                    assertTrue(guard().pending());
                    if(calls.getAndIncrement()==0) throw new IOException("Resources still alive");
                    Files.writeString(ContainerRecoveryGuard.marker(root,"registration"),"replacement");
                }
            }; }
        };
        assertEquals(WorkerStartupRecovery.Result.REQUIRES_OPERATOR,binding(resources).recover(store()));
        assertEquals(WorkerStartupRecovery.Result.REQUIRES_OPERATOR,binding(resources).recover(store()));
        assertEquals("replacement",Files.readString(ContainerRecoveryGuard.marker(root,"registration")));
    }
    static class Fake implements WorkerContainerLifecycle.Engine {
        String failure = "", state = "created"; boolean present; int removes;
        public String identity() throws Exception { if(failure.equals("engine")) throw new IOException("Engine unavailable"); return "engine"; }
        public String create(WorkerContainerLifecycle.Spec s, WorkerContainerLifecycle.Owner o) throws Exception {
            present=true; if(failure.equals("create"))throw new IOException("Lost create"); return "d".repeat(64);
        }
        public Optional<ObjectNode> inspect(String id) { return present?Optional.of(WireJson.object().set("State",WireJson.object().put("Status",state))):Optional.empty(); }
        public boolean verifySnapshot(ObjectNode v,WorkerContainerLifecycle.Spec s,WorkerContainerLifecycle.Owner o,String id) { return true; }
        public void start(String id) throws Exception { state = "running"; if(failure.equals("start"))throw new IOException("Lost start"); }
        public void remove(String id) throws Exception {
            removes++; if(failure.equals("delete"))throw new IOException("Delete unavailable"); present=false;
            if(failure.equals("lost-delete"))throw new IOException("Lost response");
        }
    }
}
