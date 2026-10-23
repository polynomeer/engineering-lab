package com.pnomeer.lab.app.findings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FindingIndexTest {
    @TempDir Path docs;
    private static final String A = "aaaaaaaa-1111-2222-3333-444444444444", B = "bbbbbbbb-1111-2222-3333-444444444444";

    @Test void indexesCitedRunIdsAndTitlesAndServesOnlyIndexedFiles() throws Exception {
        Files.writeString(docs.resolve("2026-10-01-first.md"), "# Finding: first\n`" + A + "` and `" + B + "`\n");
        Files.writeString(docs.resolve("2026-10-02-second.md"), "no title\n" + A + "\n");
        Files.writeString(docs.resolve("README.md"), B);
        Files.writeString(docs.resolve("notes.txt"), A);
        var index = new FindingIndex(docs.toString());
        assertEquals(List.of("2026-10-02-second.md", "2026-10-01-first.md"), index.findings().stream().map(FindingIndex.Finding::file).toList());
        assertEquals("Finding: first", index.findings().get(1).title());
        assertEquals("2026-10-02-second.md", index.findings().get(0).title());
        assertEquals(2, index.citing(A).size());
        assertEquals(List.of("2026-10-01-first.md"), index.citing(B).stream().map(FindingIndex.Finding::file).toList());
        assertEquals(Set.of(A, B), index.citedRunIds());
        assertTrue(index.read("2026-10-01-first.md").orElseThrow().contains(A));
        assertTrue(index.read("README.md").isEmpty());
        assertTrue(index.read("../etc/passwd.md").isEmpty());
    }

    @Test void aMissingDirectoryHasNoFindings() {
        assertTrue(FindingIndex.empty().findings().isEmpty());
    }
}
