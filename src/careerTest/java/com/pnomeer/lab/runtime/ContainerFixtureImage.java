package com.pnomeer.lab.runtime;

import com.pnomeer.lab.worker.WireJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** Assemble a test image entirely from the cached base; never resolve a temporary FROM tag remotely. */
final class ContainerFixtureImage {
    @FunctionalInterface interface Docker { String run(String... arguments) throws Exception; }
    static String create(Path directory, Path jar, String tag, Docker docker) throws Exception {
        String base = docker.run("image", "inspect", "eclipse-temurin:21-jre", "--format", "{{.Id}}").trim();
        if (!base.matches("sha256:[a-f0-9]{64}")) throw new IOException("Invalid cached base image");
        String token = UUID.randomUUID().toString();
        Path payload = Files.createDirectory(directory.resolve("lab"));
        Files.copy(jar, payload.resolve("worker.jar"));
        String id = docker.run("create", "--pull=never", "--name", "engineering-lab-image-" + token,
                "--label", "engineering-lab.image-fixture=" + token, "--network=none",
                "--entrypoint", "/bin/true", base).trim();
        if (!id.matches("[a-f0-9]{64}")) throw new IOException("Image fixture creation unconfirmed");
        try {
            // The container is never started; only this directory is added to its writable layer.
            docker.run("cp", payload.toString(), id + ":/opt/lab");
            docker.run("commit", id, tag);
            String image = docker.run("image", "inspect", tag, "--format", "{{.Id}}").trim();
            if (!image.matches("sha256:[a-f0-9]{64}")) throw new IOException("Image commit unconfirmed");
            return image;
        } finally {
            var value = WireJson.parse(docker.run("inspect", "--format", "{{json .}}", id).getBytes(StandardCharsets.UTF_8));
            if (!id.equals(value.path("Id").asText()) || !base.equals(value.path("Image").asText())
                    || !token.equals(value.path("Config").path("Labels").path("engineering-lab.image-fixture").asText())
                    || !"created".equals(value.path("State").path("Status").asText()))
                throw new IOException("Image fixture ownership or state changed");
            docker.run("rm", id);
            if (!docker.run("ps", "-a", "--no-trunc", "--quiet", "--filter", "id=" + id).isBlank())
                throw new IOException("Image fixture removal unconfirmed");
        }
    }
}
