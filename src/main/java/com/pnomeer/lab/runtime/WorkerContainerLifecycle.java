package com.pnomeer.lab.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.worker.WireJson;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** One administrator-owned journal per execution. Does not release central admission. */
public final class WorkerContainerLifecycle implements AutoCloseable {
    public enum State { EMPTY, CREATE_UNCONFIRMED, CREATED, START_UNCONFIRMED, STARTED, REMOVING, REMOVED }
    public enum Recovery { NOT_REQUIRED, REQUIRES_OPERATOR, REMOVED }
    public record Spec(String runId, String artifactSha256, String imageId, List<String> command) {
        public Spec {
            command = List.copyOf(command);
            if (runId == null || !runId.matches("[a-zA-Z0-9._-]{1,200}")
                    || artifactSha256 == null || !artifactSha256.matches("[a-f0-9]{64}")
                    || imageId == null || !imageId.matches("sha256:[a-f0-9]{64}")
                    || command.isEmpty() || command.size() > 32
                    || command.stream().anyMatch(s -> s.isEmpty() || s.indexOf('\0') >= 0 || s.length() > 1024)
                    || command.stream().mapToInt(String::length).sum() > 2048)
                throw new IllegalArgumentException("Invalid container deployment");
        }
    }
    public record Owner(String engineId, String token) {
        public String name() { return "engineering-lab-worker-" + token; }
    }
    public interface Engine {
        String identity() throws Exception;
        String create(Spec spec, Owner owner) throws Exception;
        Optional<ObjectNode> inspect(String id) throws Exception;
        void start(String id) throws Exception;
        void remove(String id) throws Exception;
    }
    private static final List<String> STEPS = List.of("intent", "created", "starting", "started", "removing", "removed");
    private final Path directory;
    private final Spec spec;
    private final Engine engine;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private Owner owner;
    private String id, intentHash;
    private State state;

    public WorkerContainerLifecycle(Path directory, Spec spec, Engine engine) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid lifecycle directory");
        this.directory = directory.toRealPath(); this.spec = Objects.requireNonNull(spec); this.engine = Objects.requireNonNull(engine);
        Path lockPath = this.directory.resolve(".lock");
        if (!Files.notExists(lockPath, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid lifecycle lock");
        lockChannel = FileChannel.open(this.directory.resolve(".lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            try { acquired = lockChannel.tryLock(); } catch (OverlappingFileLockException e) { throw new IOException("Lifecycle already owned", e); }
            if (acquired == null) throw new IOException("Lifecycle already owned");
            lock = acquired;
            reload();
        } catch (IOException | RuntimeException e) {
            if (acquired != null) acquired.release();
            lockChannel.close(); throw e;
        }
    }
    public State state() throws IOException { requireOpen(); reload(); return state; }
    public String create() throws Exception {
        if (state() != State.EMPTY) throw new IOException("Container creation already attempted");
        String identity = engine.identity();
        if (identity == null || !identity.matches("[a-zA-Z0-9:-]{1,200}")) throw new IOException("Invalid engine identity");
        owner = new Owner(identity, UUID.randomUUID().toString());
        var intent = WireJson.object().put("version", 1).put("profile", "isolated-v1")
                .put("runId", spec.runId()).put("artifactSha256", spec.artifactSha256()).put("imageId", spec.imageId())
                .put("engineId", identity).put("token", owner.token());
        var command = intent.putArray("command"); spec.command().forEach(command::add);
        write("intent", intent);
        reload(); // No create request until its intent is durable and readable.
        requireEngine();
        String created = engine.create(spec, owner);
        requireId(created);
        requireEngine();
        var snapshot = engine.inspect(created).orElseThrow(() -> new IOException("Created container missing"));
        verify(snapshot, created);
        if (!snapshot.path("State").path("Status").asText().equals("created")) throw new IOException("Container ran before ID was recorded");
        write("created", receipt().put("id", created));
        reload(); return id;
    }
    public void start() throws Exception {
        if (state() != State.CREATED) throw new IOException("Container is not eligible for start");
        requireEngine();
        var before = engine.inspect(id).orElseThrow(() -> new IOException("Container missing"));
        verify(before, id);
        if (!before.path("State").path("Status").asText().equals("created")) throw new IOException("Container state changed before start");
        write("starting", receipt());
        engine.start(id);
        requireEngine();
        var after = engine.inspect(id).orElseThrow(() -> new IOException("Started container missing"));
        verify(after, id);
        if (!Set.of("running", "exited").contains(after.path("State").path("Status").asText()))
            throw new IOException("Container start state unconfirmed");
        write("started", receipt());
        reload();
    }
    public Recovery reconcile() throws Exception {
        State current = state();
        if (current == State.EMPTY) return Recovery.NOT_REQUIRED;
        if (current == State.CREATE_UNCONFIRMED || current == State.START_UNCONFIRMED) return Recovery.REQUIRES_OPERATOR;
        // Never interpret empty query results from another engine as successful cleanup.
        requireEngine();
        var container = engine.inspect(id);
        if (current == State.REMOVED) {
            if (container.isPresent()) throw new IOException("Removed container reappeared");
            requireEngine(); return Recovery.REMOVED;
        }
        if (container.isPresent()) verify(container.get(), id);
        if (current != State.REMOVING) write("removing", receipt());
        if (container.isPresent()) { requireEngine(); engine.remove(id); }
        requireEngine();
        if (engine.inspect(id).isPresent()) throw new IOException("Container removal unconfirmed");
        requireEngine();
        write("removed", receipt()); reload();
        return Recovery.REMOVED;
    }
    private void requireEngine() throws Exception {
        if (!owner.engineId().equals(engine.identity())) throw new IOException("Docker engine changed");
    }
    private ObjectNode receipt() { return WireJson.object().put("intentSha256", intentHash); }
    private void requireOpen() throws IOException { if (!lock.isValid()) throw new IOException("Lifecycle is closed"); }
    private void reload() throws IOException {
        var records = new LinkedHashMap<String, ObjectNode>();
        for (String step : STEPS) {
            Path path = directory.resolve(step + ".json");
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid lifecycle record");
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(4097);
                if (bytes.length > 4096) throw new IOException("Lifecycle record too large");
                records.put(step, WireJson.parse(bytes));
                if (step.equals("intent")) intentHash = hash(bytes);
            }
        }
        state = State.EMPTY; id = null; owner = null;
        if (records.isEmpty()) return;
        var intent = records.get("intent");
        if (intent == null || intent.size() != 8 || !intent.path("version").isIntegralNumber() || intent.path("version").asInt() != 1
                || !intent.path("profile").asText().equals("isolated-v1")
                || !spec.runId().equals(intent.path("runId").asText())
                || !spec.artifactSha256().equals(intent.path("artifactSha256").asText())
                || !spec.imageId().equals(intent.path("imageId").asText())
                || !intent.path("engineId").asText().matches("[a-zA-Z0-9:-]{1,200}")
                || !intent.path("token").asText().matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
            throw new IOException("Invalid lifecycle intent");
        var expectedCommand = WireJson.object().putArray("command"); spec.command().forEach(expectedCommand::add);
        if (!expectedCommand.equals(intent.path("command"))) throw new IOException("Deployment command changed");
        owner = new Owner(intent.path("engineId").asText(), intent.path("token").asText());
        for (var entry : records.entrySet()) {
            if (entry.getKey().equals("intent")) continue;
            if (!intentHash.equals(entry.getValue().path("intentSha256").asText())
                    || entry.getValue().size() != (entry.getKey().equals("created") ? 2 : 1))
                throw new IOException("Lifecycle ownership changed");
        }
        if (!records.containsKey("created")) {
            if (records.size() != 1) throw new IOException("Missing created record");
            state = State.CREATE_UNCONFIRMED; return;
        }
        id = records.get("created").path("id").asText(); requireId(id);
        if (records.containsKey("started") && !records.containsKey("starting")) throw new IOException("Missing start intent");
        if (records.containsKey("removed") && !records.containsKey("removing")) throw new IOException("Missing removal intent");
        if (records.containsKey("starting") && !records.containsKey("started")) {
            if (records.containsKey("removing")) throw new IOException("Unconfirmed start cannot be reconciled");
            state = State.START_UNCONFIRMED; return;
        }
        state = records.containsKey("removed") ? State.REMOVED : records.containsKey("removing") ? State.REMOVING
                : records.containsKey("started") ? State.STARTED : State.CREATED;
    }
    private void write(String step, ObjectNode value) throws IOException {
        requireOpen(); byte[] bytes = WireJson.encode(value);
        if (bytes.length > 4096) throw new IOException("Lifecycle record too large");
        try (var file = FileChannel.open(directory.resolve(step + ".json"), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) file.write(buffer); file.force(true);
        }
    }
    private static void requireId(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IOException("Invalid full container ID");
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private void verify(ObjectNode value, String expectedId) throws IOException {
        if (!DockerContainerEngine.matches(value, spec, owner, expectedId)) throw new IOException("Container ownership or isolation changed");
    }
    @Override public void close() throws IOException { try { lock.release(); } finally { lockChannel.close(); } }
}
