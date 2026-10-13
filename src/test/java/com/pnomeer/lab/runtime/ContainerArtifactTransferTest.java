package com.pnomeer.lab.runtime;

import com.pnomeer.lab.worker.*;
import org.apache.commons.compress.archivers.tar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ContainerArtifactTransferTest {
    @TempDir Path root;
    private void extract(InputStream input, Path target, String name, long size) throws IOException {
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            ContainerArtifactTransfer.extract(input, output, name, size);
        }
    }
    private byte[] archive(String name, byte type, boolean extra) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(bytes)) {
            var entry = new TarArchiveEntry(name, type);
            boolean link = type == TarConstants.LF_SYMLINK || type == TarConstants.LF_LINK;
            if (link) entry.setLinkName("/etc/passwd"); else entry.setSize(2);
            tar.putArchiveEntry(entry); if (!link) tar.write(new byte[]{'{', '}'}); tar.closeArchiveEntry();
            if (extra) { tar.putArchiveEntry(new TarArchiveEntry("extra")); tar.closeArchiveEntry(); }
        }
        return bytes.toByteArray();
    }
    @Test void copiesOnlyTheRequestedRegularEntry() throws Exception {
        Path target = root.resolve("report.json");
        extract(new ByteArrayInputStream(archive("report.json", TarConstants.LF_NORMAL, false)), target, "report.json", 2);
        assertEquals("{}", Files.readString(target));
    }
    @Test void rejectsSymbolicAndHardLinks() throws Exception {
        for (byte type : new byte[]{TarConstants.LF_SYMLINK, TarConstants.LF_LINK}) {
            Path target = root.resolve("target-" + type);
            assertThrows(ProtocolException.class, () -> extract(new ByteArrayInputStream(archive("report.json", type, false)), target, "report.json", 0));
        }
    }
    @Test void rejectsExtraEntryAndIncorrectDeclaredSize() throws Exception {
        assertThrows(ProtocolException.class, () -> extract(new ByteArrayInputStream(archive("report.json", TarConstants.LF_NORMAL, true)), root.resolve("extra"), "report.json", 2));
        assertThrows(ProtocolException.class, () -> extract(new ByteArrayInputStream(archive("report.json", TarConstants.LF_NORMAL, false)), root.resolve("size"), "report.json", 1));
    }
    @Test void archiveCannotChooseDestination() throws Exception {
        assertThrows(ProtocolException.class, () -> extract(new ByteArrayInputStream(archive("../escape", TarConstants.LF_NORMAL, false)), root.resolve("target"), "report.json", 2));
        assertFalse(Files.exists(root.getParent().resolve("escape")));

    }
    @ParameterizedTest @ValueSource(strings={"../report", "folder/report", "/report", ".", "report:stream"})
    void rejectsUnsupportedPathsBeforeStartingDocker(String path) {
        var payload = WireJson.object().put("kind", "REPORT").put("path", path).put("sizeBytes", 2);
        assertThrows(ProtocolException.class, () -> new ContainerArtifactTransfer().copy("not-a-container", root, payload));
    }
    @Test void rejectsOversizedReportBeforeStartingDocker() {
        var payload = WireJson.object().put("kind", "REPORT").put("path", "report").put("sizeBytes", 16L * 1024 * 1024 + 1);
        assertThrows(ProtocolException.class, () -> new ContainerArtifactTransfer().copy("not-a-container", root, payload));
    }
}
