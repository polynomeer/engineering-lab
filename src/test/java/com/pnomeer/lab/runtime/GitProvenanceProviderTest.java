package com.pnomeer.lab.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class GitProvenanceProviderTest {
    @TempDir Path directory;

    @Test
    void capturesCleanTrackedAndUntrackedChangesAndUnknownRepository() throws Exception {
        var provider = new GitProvenanceProvider(directory, Duration.ofSeconds(2));
        assertNull(provider.get().dirty());
        assertNotNull(provider.get().error());
        git("init");
        git("config", "user.email", "test@example.invalid");
        git("config", "user.name", "Test");
        Files.writeString(directory.resolve("input.txt"), "first");
        git("add", "input.txt");
        git("commit", "-m", "test: initialize");
        var clean = provider.get();
        assertEquals(false, clean.dirty());
        assertNotNull(clean.commit());
        assertNull(clean.error());
        Files.writeString(directory.resolve("input.txt"), "changed");
        assertEquals(true, provider.get().dirty());
        git("checkout", "--", "input.txt");
        Files.writeString(directory.resolve("new.txt"), "untracked");
        assertEquals(true, provider.get().dirty());
        assertEquals(clean.commit(), provider.get().commit());
    }

    private void git(String... args) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of("git", "-C", directory.toString()));
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
    }
}
