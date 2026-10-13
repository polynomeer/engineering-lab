package com.pnomeer.lab.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.worker.WireJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkerContainerLifecycleTest {
    @TempDir Path root;
    private static final String ID = "b".repeat(64);
    private final WorkerContainerLifecycle.Spec spec = new WorkerContainerLifecycle.Spec("run", "a".repeat(64),
            "sha256:" + "c".repeat(64), List.of("sleep", "60"));
    private final Fake engine = new Fake();
    private WorkerContainerLifecycle open() throws IOException { return new WorkerContainerLifecycle(root, spec, engine); }
    @Test void persistsBeforeRequestsAndReopensForScopedIdempotentRemoval() throws Exception {
        try (var lifecycle = open()) {
            assertEquals(ID, lifecycle.create()); lifecycle.start();
            assertEquals(WorkerContainerLifecycle.State.STARTED, lifecycle.state());
            assertThrows(IOException.class, lifecycle::create); assertThrows(IOException.class, lifecycle::start);
        }
        try (var recovery = open()) {
            assertEquals(WorkerContainerLifecycle.Recovery.REMOVED, recovery.reconcile());
            assertEquals(WorkerContainerLifecycle.Recovery.REMOVED, recovery.reconcile());
            assertEquals(1, engine.removals);
        }
        assertTrue(Files.exists(root.resolve("intent.json")));
        assertTrue(Files.exists(root.resolve("removed.json")));
    }
    @ParameterizedTest @ValueSource(strings={"create", "start"})
    void lostMutationResponsesNeverReplayOrReconcile(String point) throws Exception {
        engine.failure = point;
        try (var lifecycle = open()) {
            if (point.equals("create")) assertThrows(IOException.class, lifecycle::create);
            else { lifecycle.create(); assertThrows(IOException.class, lifecycle::start); }
        }
        int calls = engine.calls;
        try (var recovery = open()) {
            assertEquals(WorkerContainerLifecycle.Recovery.REQUIRES_OPERATOR, recovery.reconcile());
            assertThrows(IOException.class, recovery::create); assertThrows(IOException.class, recovery::start);
        }
        assertEquals(calls, engine.calls, "Uncertain mutations must not make Docker requests");
        assertNotNull(engine.container);
    }
    @Test void lostDeleteResponseReconcilesAbsenceWithoutDeletingAgain() throws Exception {
        try (var lifecycle = open()) {
            lifecycle.create(); lifecycle.start(); engine.failure = "remove";
            assertThrows(IOException.class, lifecycle::reconcile);
        }
        engine.failure = "";
        try (var recovery = open()) { assertEquals(WorkerContainerLifecycle.Recovery.REMOVED, recovery.reconcile()); }
        assertEquals(1, engine.removals);
    }
    @ParameterizedTest @ValueSource(strings={"engine", "owner", "image", "privileged", "mount", "command"})
    void mismatchedIdentityOrIsolationNeverRemoves(String mismatch) throws Exception {
        try (var lifecycle = open()) { lifecycle.create(); }
        switch (mismatch) {
            case "engine" -> engine.identity = "other";
            case "owner" -> ((ObjectNode)engine.container.path("Config").path("Labels")).put(DockerContainerEngine.OWNER, "other");
            case "image" -> engine.container.put("Image", "sha256:" + "d".repeat(64));
            case "privileged" -> ((ObjectNode)engine.container.path("HostConfig")).put("Privileged", true);
            case "mount" -> engine.container.withArray("Mounts").addObject().put("Type", "bind").put("Destination", "/host");
            case "command" -> ((ObjectNode)engine.container.path("Config")).putArray("Cmd").add("wrong");
        }
        try (var recovery = open()) { assertThrows(IOException.class, recovery::reconcile); }
        assertEquals(0, engine.removals); assertFalse(Files.exists(root.resolve("removing.json")));
    }
    @ParameterizedTest @ValueSource(strings={"partial", "oversize", "link", "foreign", "order"})
    void invalidJournalBlocksBeforeAnyDockerRequest(String mode) throws Exception {
        try (var lifecycle = open()) { lifecycle.create(); }
        Path record = root.resolve("created.json");
        switch (mode) {
            case "partial" -> Files.writeString(record, "{");
            case "oversize" -> Files.writeString(record, "x".repeat(4097));
            case "link" -> { Files.move(record, root.resolve("original")); Files.createSymbolicLink(record, root.resolve("original")); }
            case "foreign" -> Files.writeString(record, "{\"intentSha256\":\"wrong\",\"id\":\"" + ID + "\"}");
            case "order" -> Files.copy(record, root.resolve("started.json"));
        }
        int calls = engine.calls;
        assertThrows(IOException.class, () -> { try (var ignored = open()) { fail("Invalid journal opened"); } });
        assertEquals(calls, engine.calls);
    }
    @Test void lockAndChangedDeploymentBlockBeforeSideEffects() throws Exception {
        try (var first = open()) {
            assertThrows(IOException.class, this::open); first.create();
        }
        int calls = engine.calls;
        var changed = new WorkerContainerLifecycle.Spec("run", "d".repeat(64), spec.imageId(), spec.command());
        assertThrows(IOException.class, () -> new WorkerContainerLifecycle(root, changed, engine));
        assertEquals(calls, engine.calls);
    }
    @Test void createdButNeverStartedCanBeRemovedAndClosedHandleCannotMutate() throws Exception {
        var lifecycle = open(); lifecycle.create();
        assertEquals(WorkerContainerLifecycle.Recovery.REMOVED, lifecycle.reconcile()); lifecycle.close();
        assertThrows(IOException.class, lifecycle::start);
    }
    @Test void externalStateChangeCannotIssueAnotherStart() throws Exception {
        try (var lifecycle = open()) {
            lifecycle.create();
            ((ObjectNode)engine.container.path("State")).put("Status", "running");
            assertThrows(IOException.class, lifecycle::start);
            assertFalse(Files.exists(root.resolve("starting.json")));
        }
    }
    private class Fake implements WorkerContainerLifecycle.Engine {
        String failure = "", identity = "engine";
        ObjectNode container;
        int calls, removals;
        public String identity() { calls++; return identity; }
        public String create(WorkerContainerLifecycle.Spec s, WorkerContainerLifecycle.Owner owner) throws Exception {
            calls++; assertTrue(Files.exists(root.resolve("intent.json")));
            container = snapshot(s, owner);
            if (failure.equals("create")) throw new IOException("Response lost after create");
            return ID;
        }
        public Optional<ObjectNode> inspect(String id) { calls++; assertEquals(ID, id); return Optional.ofNullable(container); }
        public void start(String id) throws Exception {
            calls++; assertTrue(Files.exists(root.resolve("starting.json"))); assertEquals(ID, id);
            ((ObjectNode)container.path("State")).put("Status", "running");
            if (failure.equals("start")) throw new IOException("Response lost after start");
        }
        public void remove(String id) throws Exception {
            calls++; removals++; assertEquals(ID, id); assertTrue(Files.exists(root.resolve("removing.json")));
            container = null;
            if (failure.equals("remove")) throw new IOException("Response lost after remove");
        }
    }
    private static ObjectNode snapshot(WorkerContainerLifecycle.Spec spec, WorkerContainerLifecycle.Owner owner) throws Exception {
        var value = WireJson.parse("""
                {"Config":{"User":"65534:65534","Entrypoint":["sleep"],"Cmd":["60"],"Labels":{}},
                 "HostConfig":{"Privileged":false,"ReadonlyRootfs":true,"AutoRemove":false,"PidMode":"",
                   "IpcMode":"private","NetworkMode":"none","RestartPolicy":{"Name":"no"},
                   "Memory":268435456,"NanoCpus":1000000000,"PidsLimit":64,"LogConfig":{"Type":"none"},
                   "CapDrop":["ALL"],"CapAdd":null,"SecurityOpt":["no-new-privileges"],"Binds":null,"Devices":[],
                   "PortBindings":{},"Tmpfs":{"/tmp":"rw,nosuid,nodev,noexec,size=16m"}},
                 "Mounts":[],"State":{"Status":"created"}}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        value.put("Id", ID).put("Image", spec.imageId()).put("Name", "/" + owner.name());
        ((ObjectNode)value.path("Config").path("Labels")).put(DockerContainerEngine.OWNER, owner.token())
                .put(DockerContainerEngine.RUN, spec.runId()).put(DockerContainerEngine.ARTIFACT, spec.artifactSha256());
        return value;
    }
}
