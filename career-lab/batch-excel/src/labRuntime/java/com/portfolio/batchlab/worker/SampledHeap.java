package com.portfolio.batchlab.worker;

import java.lang.management.ManagementFactory;
import java.util.function.LongSupplier;

/** Constant-memory samples; no explicit GC, no claim to capture the true peak or live set. */
public final class SampledHeap implements AutoCloseable {
    public static final long INTERVAL_MS=20;
    public record Result(long baselineBytes,long sampledPeakBytes,long samples,long elapsedNanos,long startEpochMs,long endEpochMs) {}
    private final LongSupplier used;
    private final Thread thread;
    private final long baseline, began=System.nanoTime(), start=System.currentTimeMillis();
    private long peak, samples;
    private volatile boolean running=true;
    private Result result;
    public SampledHeap(){this(()->ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());}
    SampledHeap(LongSupplier used){
        this.used=used; baseline=used.getAsLong(); peak=baseline; samples=1;
        thread=new Thread(()->{while(running){try{Thread.sleep(INTERVAL_MS);}catch(InterruptedException e){break;}if(running) sample();}},"sampled-heap");
        thread.setDaemon(true);thread.start();
    }
    private synchronized void sample(){peak=Math.max(peak,used.getAsLong());samples++;}
    public synchronized Result result(){if(result==null)throw new IllegalStateException("Sampler still active");return result;}
    public void close(){
        if(result!=null)return;
        running=false;thread.interrupt();boolean interrupted=false;
        while(thread.isAlive())try{thread.join();}catch(InterruptedException e){interrupted=true;}
        synchronized(this){sample();result=new Result(baseline,peak,samples,System.nanoTime()-began,start,System.currentTimeMillis());}
        if(interrupted)Thread.currentThread().interrupt();
    }
}
