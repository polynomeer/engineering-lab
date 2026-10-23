package com.pnomeer.lab.metrics;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class HeapSamplerTest {
    @Test void recordsAPositivePeakAndGcTimeOnlyAfterClose() throws Exception {
        var sampler = HeapSampler.start(Duration.ofMillis(5));
        byte[][] garbage = new byte[64][];
        for (int i = 0; i < garbage.length; i++) garbage[i] = new byte[64 * 1024];
        Thread.sleep(30);
        assertEquals(-1, sampler.gcMillis());
        sampler.close();
        assertTrue(sampler.peakBytes() > 0);
        assertTrue(sampler.gcMillis() >= 0);
        assertTrue(sampler.maxBytes() == -1 || sampler.maxBytes() >= sampler.peakBytes());
        long peak = sampler.peakBytes();
        sampler.close(); // idempotent
        assertEquals(peak, sampler.peakBytes());
        assertNotNull(garbage[63]);
    }
}
