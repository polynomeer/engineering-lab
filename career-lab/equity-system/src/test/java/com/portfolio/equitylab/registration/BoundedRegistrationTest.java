package com.portfolio.equitylab.registration;

import com.portfolio.equitylab.repository.EquityShareInsertDao;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BoundedRegistrationTest {
    @Test void boundedWindowFlushesPartialChunkAndMeasuresAdmissionWait() {
        var processor=mock(RowProcessor.class); var dao=mock(EquityShareInsertDao.class);
        AtomicInteger inserted=new AtomicInteger();
        doAnswer(call->{Thread.sleep(10); inserted.addAndGet(((List<?>)call.getArgument(0)).size());return null;}).when(dao).insertBatch(anyList());
        var result=new ParallelRegistrationPipeline(processor,dao).registerMeasured(Collections.nCopies(103,null),10,1,2);
        assertEquals(103,result.insertedRows()); assertEquals(103,inserted.get()); assertEquals(2,result.maxInFlightChunks());
        assertTrue(result.backpressureWaits()>0); assertTrue(result.backpressureNanos()>0);
        verify(dao,times(11)).insertBatch(anyList());
    }
    @Test void rejectsInvalidBoundsAndDoesNotSilentlyDropFailure() {
        var processor=mock(RowProcessor.class); var dao=mock(EquityShareInsertDao.class);
        var pipeline=new ParallelRegistrationPipeline(processor,dao);
        assertThrows(IllegalArgumentException.class,()->pipeline.register(List.of(),0));
        assertThrows(IllegalArgumentException.class,()->pipeline.registerMeasured(List.of(),10,1,33));
        when(processor.process(null)).thenThrow(new IllegalArgumentException("invalid share"));
        var failure=assertThrows(IllegalStateException.class,()->pipeline.registerMeasured(Collections.nCopies(100,null),10,1,1));
        assertEquals("invalid share",failure.getCause().getMessage()); verifyNoInteractions(dao);
    }
}
