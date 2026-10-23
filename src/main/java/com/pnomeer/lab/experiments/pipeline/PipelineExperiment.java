package com.pnomeer.lab.experiments.pipeline;

import com.pnomeer.lab.core.Experiment;
import com.pnomeer.lab.core.ExperimentSummary;
import com.pnomeer.lab.core.MetricPoint;
import com.pnomeer.lab.app.ingest.PipelineMetricPointMapper;
import com.pnomeer.lab.metrics.ExecutionMetricsSnapshot;
import com.pnomeer.lab.metrics.HeapSampler;
import com.pnomeer.lab.experiments.pipeline.fork.ForkedPipeline;
import com.pnomeer.lab.experiments.pipeline.model.ValidationError;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
public class PipelineExperiment implements Experiment<PipelineExperimentScenario> {
    private final PipelineRunner pipelineRunner;
    private final int forkedHeapMiB;

    @org.springframework.beans.factory.annotation.Autowired
    public PipelineExperiment(PipelineRunner pipelineRunner) {
        this(pipelineRunner, 0);
    }

    /** {@code forkedHeapMiB > 0} runs each pass in a child JVM with that heap limit instead of in this JVM. */
    public PipelineExperiment(PipelineRunner pipelineRunner, int forkedHeapMiB) {
        if (forkedHeapMiB != 0 && (forkedHeapMiB < ForkedPipeline.MIN_HEAP_MIB || forkedHeapMiB > ForkedPipeline.MAX_HEAP_MIB))
            throw new IllegalArgumentException("forkedHeapMiB must be 0 or 32..8192");
        this.pipelineRunner = pipelineRunner;
        this.forkedHeapMiB = forkedHeapMiB;
    }

    @Override
    public String type() {
        return "pipeline.excel-ingestion";
    }

    @Override
    public Map<String, String> parameters(PipelineExperimentScenario scenario) {
        var parameters = new java.util.HashMap<>(scenario.parameters());
        parameters.putAll(pipelineRunner.configurationSnapshot());
        if (forkedHeapMiB > 0) {
            parameters.put("execution.isolation", "FORKED_JVM");
            parameters.put("execution.forked.heapMiB", String.valueOf(forkedHeapMiB));
            parameters.put("execution.forked.database", "H2 in-memory per run, pool 10");
        } else parameters.put("execution.isolation", "IN_PROCESS");
        return Map.copyOf(parameters);
    }

    @Override
    public ExperimentSummary execute(PipelineExperimentScenario scenario, Consumer<MetricPoint> timelineListener)
            throws Exception {
        return executeWithDeadLetters(scenario, timelineListener, row -> { });
    }

    @Override
    public ExperimentSummary execute(PipelineExperimentScenario scenario, Consumer<MetricPoint> timelineListener,
            com.pnomeer.lab.core.TraceRecorder trace, String parentSpanId, com.pnomeer.lab.core.ExecutionEvidence evidence) throws Exception {
        return executeWithDeadLetters(scenario, timelineListener, evidence.deadLetters());
    }

    private ExperimentSummary executeWithDeadLetters(PipelineExperimentScenario scenario, Consumer<MetricPoint> timelineListener,
            Consumer<com.pnomeer.lab.core.DeadLetter> deadLetters) throws Exception {
        if (forkedHeapMiB > 0) {
            var forked = ForkedPipeline.run(scenario.payload(), scenario.runMode(), pipelineRunner.configurationSnapshot(), forkedHeapMiB,
                    timelineListener, deadLetters);
            scenario.capture().setRunResult(forked.result());
            return toSummary(forked.result(), forked.heapPeakBytes(), forked.heapMaxBytes(), forked.gcMillis());
        }
        PipelineRunner.PipelineRunResult result;
        var heap = HeapSampler.start();
        try (heap; var input = scenario.payload().openStream()) {
            result = pipelineRunner.run(
                    input,
                    scenario.runMode(),
                    progressSnapshot -> publishProgress(scenario, timelineListener, progressSnapshot),
                    error -> deadLetters.accept(deadLetter(error)));
            scenario.capture().setRunResult(result);
        }
        return toSummary(result, heap.peakBytes(), heap.maxBytes(), heap.gcMillis());
    }

    private static com.pnomeer.lab.core.DeadLetter deadLetter(ValidationError error) {
        return new com.pnomeer.lab.core.DeadLetter(error.getRowIndex(), "validation", error.getReason(), error.getRawSnapshot().getCells());
    }

    private static void publishProgress(
            PipelineExperimentScenario scenario,
            Consumer<MetricPoint> timelineListener,
            ExecutionMetricsSnapshot progressSnapshot) {
        scenario.progressListener().accept(progressSnapshot);
        timelineListener.accept(PipelineMetricPointMapper.fromProgressSnapshot(progressSnapshot));
    }

    static ExperimentSummary toSummary(PipelineRunner.PipelineRunResult result, long heapPeakBytes, long heapMaxBytes, long gcMillis) {
        Map<String, Long> errorSummary = result.getValidationReasons();
        Map<String, Long> counters = Map.of(
                "retry.count", (long) result.getRetryCount(),
                "rows.produced", (long) result.getProducedCount(),
                "rows.inserted", (long) result.getInsertedCount(),
                "rows.validationErrors", result.getValidationErrorCount(),
                "heap.sampledPeakBytes", heapPeakBytes,
                "heap.maxBytes", heapMaxBytes,
                "gc.timeMs", gcMillis);
        var gauges = new java.util.HashMap<String, Double>();
        gauges.put("timing.elapsedMs", (double) result.getElapsedMillis());
        // No rate for a zero-length window: a made-up denominator would be a guess, not a measurement.
        if (result.getElapsedMillis() > 0)
            gauges.put("throughput.insertedRowsPerSec", result.getInsertedCount() * 1000.0 / result.getElapsedMillis());
        Map<String, String> details = errorSummary.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> "validation." + entry.getKey(),
                        entry -> String.valueOf(entry.getValue())));
        return new ExperimentSummary(counters, gauges, details);
    }
}
