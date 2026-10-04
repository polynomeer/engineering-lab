package com.pnomeer.lab.core.input;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InputPayloadTest {
    @Test void stagesLargeStreamWithSmallReadsAndReleasesEveryAdmissionSlot() throws Exception {
        var held = new ArrayList<InputPayload>();
        try {
            var source = new InputStream() {
                int remaining = 12 * 1024 * 1024;
                @Override public int read() { throw new AssertionError("Use buffered reads"); }
                @Override public int read(byte[] b, int offset, int length) {
                    assertTrue(length <= 8192);
                    if (remaining == 0) return -1;
                    int count = Math.min(length, remaining); Arrays.fill(b, offset, offset + count, (byte)7); remaining -= count; return count;
                }
            };
            var payload = InputPayload.stage(source, InputPayload.MAX_BYTES); held.add(payload);
            assertEquals(12 * 1024 * 1024, payload.size());
            try (var input = payload.openStream()) { assertEquals(payload.size(), input.transferTo(OutputStream.nullOutputStream())); }
            for (int i = 1; i < InputPayload.MAX_STAGED; i++) held.add(InputPayload.stage(new ByteArrayInputStream(new byte[]{1}), 10));
            assertThrows(IllegalStateException.class, () -> InputPayload.stage(new ByteArrayInputStream(new byte[0]), 1));
        } finally { held.forEach(InputPayload::close); }
        assertThrows(IOException.class, held.getFirst()::openStream);
        held.getFirst().close();
        try (var replacement = InputPayload.stage(new ByteArrayInputStream(new byte[]{1,2,3}), 3)) {
            assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", replacement.sha256());
        }
    }
    @Test void oversizeAndReadFailureReleaseStagingCapacity() throws Exception {
        for (int i = 0; i < InputPayload.MAX_STAGED + 1; i++) {
            assertThrows(IOException.class, () -> InputPayload.stage(new ByteArrayInputStream(new byte[11]), 10));
            assertThrows(IOException.class, () -> InputPayload.stage(new InputStream() {
                @Override public int read() throws IOException { throw new IOException("injected"); }
            }, 10));
        }
        try (var payload = InputPayload.stage(new ByteArrayInputStream(new byte[]{1}), 10)) { assertEquals(1, payload.size()); }
    }
}
