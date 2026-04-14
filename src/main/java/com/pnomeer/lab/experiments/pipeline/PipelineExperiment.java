package com.pnomeer.lab.experiments.pipeline;

import com.pnomeer.lab.core.Experiment;
import com.pnomeer.lab.core.ExperimentSummary;
import com.pnomeer.lab.core.MetricPoint;
import com.pnomeer.lab.metrics.ExecutionMetricsSnapshot;
import com.pnomeer.pipeline.PipelineRunner;
import com.pnomeer.pipeline.api.PipelineMetricPointMapper;
import com.pnomeer.pipeline.model.ValidationError;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
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
    public ExperimentSummary execute(PipelineExperimentScenario scenario, Consumer<MetricPoint> timelineListener)
            throws Exception {
        PipelineRunner.PipelineRunResult result = pipelineRunner.run(
                new ByteArrayInputStream(scenario.payload()),
                scenario.runMode(),
                progressSnapshot -> publishProgress(scenario, timelineListener, progressSnapshot));
        scenario.capture().setRunResult(result);
        return toSummary(result);
    }

    private static void publishProgress(
            PipelineExperimentScenario scenario,
            Consumer<MetricPoint> timelineListener,
            ExecutionMetricsSnapshot progressSnapshot) {
        scenario.progressListener().accept(progressSnapshot);
        timelineListener.accept(PipelineMetricPointMapper.fromProgressSnapshot(progressSnapshot));
    }

    private static ExperimentSummary toSummary(PipelineRunner.PipelineRunResult result) {
        Map<String, Long> errorSummary = result.getValidationErrors().stream()
                .map(ValidationError::getReason)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Map<String, Long> counters = Map.of(
                "rows.produced", (long) result.getProducedCount(),
                "rows.inserted", (long) result.getInsertedCount(),
                "rows.validationErrors", (long) result.getValidationErrors().size());
        Map<String, Double> gauges = Map.of(
                "timing.elapsedMs", (double) result.getElapsedMillis());
        Map<String, String> details = errorSummary.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> "validation." + entry.getKey(),
                        entry -> String.valueOf(entry.getValue())));
        return new ExperimentSummary(counters, gauges, details);
    }
}
