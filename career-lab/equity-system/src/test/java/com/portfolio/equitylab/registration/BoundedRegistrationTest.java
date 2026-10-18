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
    @Test void sequentialFlushesFullAndPartialBatchesAndPropagatesWriteFailure() {
        var processor=mock(RowProcessor.class); var dao=mock(EquityShareInsertDao.class);
        var sizes=new ArrayList<Integer>();
        doAnswer(call -> { sizes.add(((List<?>)call.getArgument(0)).size()); return null; }).when(dao).insertBatch(anyList());
        var pipeline=new SequentialRegistrationPipeline(processor,dao);
        assertEquals(2003,pipeline.register(Collections.nCopies(2003,null)));
        assertEquals(List.of(1000,1000,3),sizes);
        reset(dao);
        var failure=new IllegalStateException("uncertain commit");
        doThrow(failure).when(dao).insertBatch(anyList());
        assertSame(failure,assertThrows(IllegalStateException.class,()->pipeline.register(Collections.nCopies(2003,null))));
        verify(dao,times(1)).insertBatch(anyList()); // No implicit retry or write of the tail.
    }
    @Test void parallelWriteFailureIsNotRetriedOrSilentlyCounted() {
        var processor=mock(RowProcessor.class); var dao=mock(EquityShareInsertDao.class);
        var failure=new IllegalStateException("uncertain commit");
        doThrow(failure).when(dao).insertBatch(anyList());
        var pipeline=new ParallelRegistrationPipeline(processor,dao);
        assertSame(failure,assertThrows(IllegalStateException.class,
                ()->pipeline.registerMeasured(Collections.nCopies(23,null),10,1,1)).getCause());
        verify(dao,times(1)).insertBatch(anyList());
    }
    @Test void interruptedAdmissionCancelsWorkAndRestoresCallerInterrupt() throws Exception {
        var processor=mock(RowProcessor.class); var dao=mock(EquityShareInsertDao.class);
        var started=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(processor.process(null)).thenAnswer(call -> { started.countDown(); release.await(); return null; });
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller=new Thread(()->{
            try { new ParallelRegistrationPipeline(processor,dao).registerMeasured(Collections.nCopies(30,null),10,1,1); }
            catch (Throwable ex) { failure.set(ex); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        caller.start();
        try {
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            caller.interrupt(); caller.join(5000);
            assertFalse(caller.isAlive()); assertTrue(interrupted.get());
            assertInstanceOf(InterruptedException.class,failure.get().getCause());
            verifyNoInteractions(dao);
        } finally { release.countDown(); caller.interrupt(); caller.join(5000); }
    }

}
