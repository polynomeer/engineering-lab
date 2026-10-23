package com.pnomeer.lab.experiments.pipeline;

import com.pnomeer.lab.core.Experiment;
import com.pnomeer.lab.core.ExperimentSummary;
import com.pnomeer.lab.core.MetricPoint;
import com.pnomeer.lab.app.ingest.PipelineMetricPointMapper;
import com.pnomeer.lab.metrics.ExecutionMetricsSnapshot;
import com.pnomeer.lab.metrics.HeapSampler;
import com.pnomeer.lab.experiments.pipeline.model.ValidationError;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
public class PipelineExperiment implements Experiment<PipelineExperimentScenario> {
    private final PipelineRunner pipelineRunner;

    public PipelineExperiment(PipelineRunner pipelineRunner) {
        this.pipelineRunner = pipelineRunner;
    }

    @Override
    public String type() {
        return "pipeline.excel-ingestion";
    }

    @Override
    public Map<String, String> parameters(PipelineExperimentScenario scenario) {
        var parameters = new java.util.HashMap<>(scenario.parameters());
        parameters.putAll(pipelineRunner.configurationSnapshot());
        return Map.copyOf(parameters);
    }

    @Override
    public ExperimentSummary execute(PipelineExperimentScenario scenario, Consumer<MetricPoint> timelineListener)
            throws Exception {
        return executeWithDeadLetters(scenario, timelineListener, error -> { });
    }

    @Override
    public ExperimentSummary execute(PipelineExperimentScenario scenario, Consumer<MetricPoint> timelineListener,
            com.pnomeer.lab.core.TraceRecorder trace, String parentSpanId, com.pnomeer.lab.core.ExecutionEvidence evidence) throws Exception {
        return executeWithDeadLetters(scenario, timelineListener, error -> evidence.deadLetters().accept(
                new com.pnomeer.lab.core.DeadLetter(error.getRowIndex(), "validation", error.getReason(), error.getRawSnapshot().getCells())));
    }

    private ExperimentSummary executeWithDeadLetters(PipelineExperimentScenario scenario, Consumer<MetricPoint> timelineListener,
            Consumer<ValidationError> deadLetters) throws Exception {
        PipelineRunner.PipelineRunResult result;
        var heap = HeapSampler.start();
        try (heap; var input = scenario.payload().openStream()) {
            result = pipelineRunner.run(
                    input,
                    scenario.runMode(),
                    progressSnapshot -> publishProgress(scenario, timelineListener, progressSnapshot), deadLetters);
            scenario.capture().setRunResult(result);
        }
        return toSummary(result, heap);
    }

    private static void publishProgress(
            PipelineExperimentScenario scenario,
            Consumer<MetricPoint> timelineListener,
            ExecutionMetricsSnapshot progressSnapshot) {
        scenario.progressListener().accept(progressSnapshot);
        timelineListener.accept(PipelineMetricPointMapper.fromProgressSnapshot(progressSnapshot));
    }

    static ExperimentSummary toSummary(PipelineRunner.PipelineRunResult result, HeapSampler heap) {
        Map<String, Long> errorSummary = result.getValidationReasons();
        Map<String, Long> counters = Map.of(
                "retry.count", (long) result.getRetryCount(),
                "rows.produced", (long) result.getProducedCount(),
                "rows.inserted", (long) result.getInsertedCount(),
                "rows.validationErrors", result.getValidationErrorCount(),
                "heap.sampledPeakBytes", heap.peakBytes(),
                "heap.maxBytes", heap.maxBytes(),
                "gc.timeMs", heap.gcMillis());
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
