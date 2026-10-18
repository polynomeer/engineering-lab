package com.pnomeer.lab.experiments.examples;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.pipeline.BoundedBatch;
import java.util.Map;
import java.util.function.Consumer;

/** NEW-DESIGN: generated records and an in-memory checksum sink, not database throughput. */
public final class BatchedChecksumExperiment implements Experiment<BatchedChecksumExperiment.Scenario> {
    public static final String ID = "examples.batched-checksum";
    public record Scenario(String scenarioId, int rows, int batchSize, int seed, int failBatch) implements ExperimentScenario {
        public Scenario {
            if (rows < 1 || rows > 10000 || batchSize < 1 || batchSize > 256 || seed < 0 || seed > 1000000 || failBatch < 0 || failBatch > 10000)
                throw new IllegalArgumentException("Invalid checksum bounds");
        }
        public Map<String,String> parameters() { return Map.of("rows",""+rows,"batchSize",""+batchSize,"seed",""+seed,"failBatch",""+failBatch); }
    }
    public String type() { return ID; }
    public ExperimentSummary execute(Scenario scenario, Consumer<MetricPoint> metrics) throws Exception {
        var batch = new BoundedBatch<Long>(scenario.batchSize());
        long[] totals = new long[4]; // committed rows, checksum, batches, observed batch size; constant space.
        BoundedBatch.Sink<Long, InterruptedException> sink = rows -> {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Checksum interrupted");
            if (scenario.failBatch() == totals[2] + 1) throw new IllegalStateException("Injected checksum sink failure before write");
            long checksum = 0;
            for (long value : rows) checksum += value;
            totals[0] += rows.size(); totals[1] += checksum; totals[2]++;
            totals[3] = Math.max(totals[3], rows.size());
            metrics.accept(new MetricPoint(System.currentTimeMillis(), Map.of(), counters(totals), Map.of("stage","checksum")));
        };
        for (int i=0;i<scenario.rows();i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Checksum interrupted");
            batch.add((long) scenario.seed() + i);
            if (batch.isFull()) batch.flushTo(sink);
        }
        batch.flushTo(sink);
        return new ExperimentSummary(counters(totals),Map.of(),Map.of("scope","Synthetic synchronous checksum; no DB, retry, DLQ or performance claim"));
    }
    private static Map<String,Long> counters(long[] totals) {
        return Map.of("checksum.rows",totals[0],"checksum.value",totals[1],"checksum.batches",totals[2],"checksum.maxBatchRows",totals[3]);
    }
}
