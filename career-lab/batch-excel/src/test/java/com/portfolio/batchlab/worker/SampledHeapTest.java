package com.portfolio.batchlab.worker;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SampledHeapTest {
    @Test void recordsBoundarySamplesAndClosesOnce(){
        var used=new AtomicLong(100);var probe=new SampledHeap(used::get);
        assertThrows(IllegalStateException.class,probe::result);
        used.set(200);probe.close();var result=probe.result();
        assertEquals(100,result.baselineBytes());assertEquals(200,result.sampledPeakBytes());assertTrue(result.samples()>=2);
        assertTrue(result.elapsedNanos()>0);assertTrue(result.startEpochMs()<=result.endEpochMs());
        used.set(1000);probe.close();assertEquals(result,probe.result());
    }
    @Test void samplingRetainsPeakWithoutRetainingHistory()throws Exception {
        var used=new AtomicLong(300);var probe=new SampledHeap(used::get);
        try{used.set(700);Thread.sleep(100);}finally{probe.close();}
        assertEquals(700,probe.result().sampledPeakBytes());assertTrue(probe.result().samples()>2);
    }
}
