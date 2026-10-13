package com.portfolio.mcplab.worker;

import com.pnomeer.lab.worker.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SequenceContainerRecoveryIntegrationTest {
    @TempDir Path root;
    @Test void packagedRecoveryRefusesActiveLeaseAndRemovesOnlyOwnedContainer() throws Exception {
        var client = DockerClientFactory.instance().client();
        Path jar = Path.of(System.getProperty("sequence.worker.jar"));
        String hash = hash(jar), run = UUID.randomUUID().toString();
        String owned = null, unrelated = null;
        try {
            try (var lease = SequenceContainerRecovery.lock(root)) {
                var intent = lease.create(run, hash, client.infoCmd().exec().getId());
                // Intentionally leave stopped containers outside Ryuk registration, as recovery fixtures.
                owned = client.createContainerCmd(SequenceContainerRecovery.IMAGE).withName(intent.name()).withLabels(intent.labels()).withCmd("sleep", "300").exec().getId();
                client.startContainerCmd(owned).exec();
                unrelated = client.createContainerCmd(SequenceContainerRecovery.IMAGE)
                        .withLabels(Map.of("engineering-lab.run-id", run, "engineering-lab.owner-token", UUID.randomUUID().toString())).exec().getId();
                assertNotEquals(0, recover(jar, run));
                assertEquals(owned, client.inspectContainerCmd(owned).exec().getId());
            }
            byte[] journal = Files.readAllBytes(root.resolve(SequenceContainerRecovery.JOURNAL));
            assertEquals(0, recover(jar, run));
            var output = WireJson.parse(Files.readAllBytes(root.resolve("recovery-output.json")));
            assertEquals(1, output.path("removedContainers").intValue());
            assertFalse(output.path("admissionReleased").booleanValue());
            assertEquals(unrelated, client.inspectContainerCmd(unrelated).exec().getId());
            assertEquals(0, recover(jar, run));
            assertEquals(0, WireJson.parse(Files.readAllBytes(root.resolve("recovery-output.json"))).path("removedContainers").intValue());
            assertArrayEquals(journal, Files.readAllBytes(root.resolve(SequenceContainerRecovery.JOURNAL)));
        } finally {
            for (String id : new String[]{owned, unrelated}) if (id != null) {
                try { client.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec(); }
                catch (com.github.dockerjava.api.exception.NotFoundException ignored) {}
            }
        }
    }
    private static String hash(Path jar) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var input = new java.security.DigestInputStream(Files.newInputStream(jar), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private int recover(Path jar, String run) throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-jar", jar.toString(), "--recover", root.toString(), run)
                .redirectOutput(root.resolve("recovery-output.json").toFile()).redirectError(root.resolve("recovery-error.log").toFile());
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) builder.environment().remove(key);
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(70, TimeUnit.SECONDS), "Recovery process deadline");
            return process.exitValue();
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }
}
