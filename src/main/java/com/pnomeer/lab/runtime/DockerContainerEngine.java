package com.pnomeer.lab.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.worker.WireJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Bounded local Docker CLI adapter. No implicit pull, shell, host mounts or worker Docker access. */
public final class DockerContainerEngine implements WorkerContainerLifecycle.Engine {
    public static final String OWNER = "engineering-lab.worker-owner", RUN = "engineering-lab.run-id",
            ARTIFACT = "engineering-lab.artifact-sha256";
    public String identity() throws Exception { return command("info", "--format", "{{.ID}}").trim(); }
    public String create(WorkerContainerLifecycle.Spec spec, WorkerContainerLifecycle.Owner owner) throws Exception {
        var args = new ArrayList<>(List.of("create", "--pull=never", "--name", owner.name(),
                "--label", OWNER + "=" + owner.token(), "--label", RUN + "=" + spec.runId(),
                "--label", ARTIFACT + "=" + spec.artifactSha256(), "--user", "65534:65534", "--read-only",
                "--cap-drop=ALL", "--security-opt=no-new-privileges", "--network=none", "--ipc=private", "--restart=no",
                "--memory=256m", "--cpus=1", "--pids-limit=64", "--log-driver=none",
                "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=16m", "--entrypoint", spec.command().getFirst(), spec.imageId()));
        args.addAll(spec.command().subList(1, spec.command().size()));
        return command(args.toArray(String[]::new)).trim();
    }
    public Optional<ObjectNode> inspect(String id) throws Exception {
        if (!id.matches("[a-f0-9]{64}")) throw new IOException("Full container ID required");
        String found = command("ps", "-aq", "--no-trunc", "--filter", "id=" + id).trim();
        if (found.isEmpty()) return Optional.empty();
        if (!found.equals(id)) throw new IOException("Ambiguous container lookup");
        return Optional.of(WireJson.parse(command("inspect", "--format", "{{json .}}", id).getBytes(StandardCharsets.UTF_8)));
    }
    public void start(String id) throws Exception { requireId(id); command("start", id); }
    public void remove(String id) throws Exception { requireId(id); command("rm", "--force", "--volumes", id); }
    private static void requireId(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IOException("Full container ID required");
    }
    static boolean matches(ObjectNode value, WorkerContainerLifecycle.Spec spec, WorkerContainerLifecycle.Owner owner, String id) {
        var config = value.path("Config"); var host = value.path("HostConfig"); var labels = config.path("Labels");
        if (!id.equals(value.path("Id").asText()) || !spec.imageId().equals(value.path("Image").asText())
                || !("/" + owner.name()).equals(value.path("Name").asText())
                || !owner.token().equals(labels.path(OWNER).asText()) || !spec.runId().equals(labels.path(RUN).asText())
                || !spec.artifactSha256().equals(labels.path(ARTIFACT).asText())
                || !"65534:65534".equals(config.path("User").asText())
                || !value.path("State").path("Status").isTextual()
                || !host.path("Privileged").isBoolean() || host.path("Privileged").asBoolean()
                || !host.path("ReadonlyRootfs").asBoolean() || host.path("AutoRemove").asBoolean()
                || !host.path("PidMode").asText("missing").isEmpty() || !"private".equals(host.path("IpcMode").asText())
                || !"none".equals(host.path("NetworkMode").asText())
                || !"no".equals(host.path("RestartPolicy").path("Name").asText())
                || host.path("Memory").asLong() != 256L * 1024 * 1024 || host.path("NanoCpus").asLong() != 1000000000L
                || host.path("PidsLimit").asInt() != 64 || !"none".equals(host.path("LogConfig").path("Type").asText())
                || !array(host.path("CapDrop"), List.of("ALL")) || !empty(host.path("CapAdd"))
                || !array(host.path("SecurityOpt"), List.of("no-new-privileges"))
                || !empty(host.path("Binds")) || !empty(host.path("Devices")) || !empty(host.path("PortBindings"))
                || !array(config.path("Entrypoint"), spec.command().subList(0, 1))
                || !(array(config.path("Cmd"), spec.command().subList(1, spec.command().size()))
                    || spec.command().size() == 1 && config.path("Cmd").isNull())) return false;
        if (!value.path("Mounts").isArray()) return false;
        for (var mount : value.path("Mounts"))
            if (!"tmpfs".equals(mount.path("Type").asText()) || !"/tmp".equals(mount.path("Destination").asText())) return false;
        return host.path("Tmpfs").size() == 1
                && "rw,nosuid,nodev,noexec,size=16m".equals(host.path("Tmpfs").path("/tmp").asText());
    }
    private static boolean empty(JsonNode node) { return node.isNull() || (node.isContainerNode() && node.isEmpty()); }
    private static boolean array(JsonNode node, List<String> expected) {
        if (!node.isArray() || node.size() != expected.size()) return false;
        for (int i = 0; i < expected.size(); i++) if (!expected.get(i).equals(node.path(i).asText())) return false;
        return true;
    }
    private static String command(String... arguments) throws Exception {
        var args = new ArrayList<>(List.of("docker")); args.addAll(List.of(arguments));
        Process child = new ProcessBuilder(args).redirectErrorStream(true).start();
        var io = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "container-engine-cli"); t.setDaemon(true); return t; });
        boolean interrupted = false;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            byte[] bytes = io.submit(() -> child.getInputStream().readNBytes(65537)).get(30, TimeUnit.SECONDS);
            if (bytes.length > 65536 || !child.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                    || child.exitValue() != 0) throw new IOException("Docker command failed or exceeded output/deadline limit");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (InterruptedException e) { interrupted = true; throw e; }
        finally {
            interrupted |= Thread.interrupted();
            try {
                if (child.isAlive()) { child.destroyForcibly(); if (!child.waitFor(2, TimeUnit.SECONDS)) throw new IOException("Docker CLI cleanup unconfirmed"); }
                io.shutdownNow(); if (!io.awaitTermination(2, TimeUnit.SECONDS)) throw new IOException("Docker CLI IO cleanup unconfirmed");
            } catch (InterruptedException e) { interrupted = true; throw e; }
            finally { io.shutdownNow(); if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
}
