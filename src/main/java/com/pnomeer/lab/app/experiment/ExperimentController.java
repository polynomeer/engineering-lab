package com.pnomeer.lab.app.experiment;

import com.pnomeer.lab.core.ExperimentStatus;
import com.pnomeer.lab.experiments.concurrency.QueueContentionScenario;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/experiments")
public class ExperimentController {
    private final ExperimentJobService experimentJobService;

    public ExperimentController(ExperimentJobService experimentJobService) {
        this.experimentJobService = experimentJobService;
    }

    @PostMapping("/concurrency/queue-contention")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartExperimentResponse startQueueContention(@RequestBody QueueContentionRequest request) {
        QueueContentionScenario scenario = new QueueContentionScenario(
                request.scenarioId() == null || request.scenarioId().isBlank() ? "queue-contention" : request.scenarioId(),
                request.queueCapacity(),
                request.producerThreads(),
                request.consumerThreads(),
                request.itemsPerProducer(),
                request.consumerDelayMs());
        return new StartExperimentResponse(
                experimentJobService.startQueueContentionJob(scenario),
                "concurrency.queue-contention",
                scenario.scenarioId());
    }

    @GetMapping("/jobs")
    public ExperimentJobsResponse listJobs() {
        List<ExperimentJobResponse> jobs = experimentJobService.listJobs().stream()
                .map(this::toResponse)
                .toList();
        return new ExperimentJobsResponse(jobs);
    }

    @GetMapping("/jobs/{jobId}")
    public ExperimentJobResponse getJob(@PathVariable String jobId) {
        ExperimentJobState state = experimentJobService.getJob(jobId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found");
        }
        return toResponse(state);
    }

    @GetMapping("/jobs/{jobId}/metrics")
    public ExperimentMetricTimelineResponse getJobMetrics(@PathVariable String jobId) {
        ExperimentJobState state = experimentJobService.getJob(jobId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found");
        }
        return new ExperimentMetricTimelineResponse(
                state.getJobId(),
                state.getExperimentType(),
                state.getScenarioId(),
                state.getTimeline());
    }

    private ExperimentJobResponse toResponse(ExperimentJobState state) {
        return new ExperimentJobResponse(
                state.getJobId(),
                state.getExperimentType(),
                state.getScenarioId(),
                state.getStatus(),
                state.getCreatedAtEpochMs(),
                state.getUpdatedAtEpochMs(),
                state.getStartedAtEpochMs(),
                state.getCompletedAtEpochMs(),
                state.getFirstMetricAtEpochMs(),
                state.getFailureMessage(),
                state.getSummary().counters(),
                state.getSummary().gauges(),
                state.getSummary().details());
    }

    public record QueueContentionRequest(
            String scenarioId,
            int queueCapacity,
            int producerThreads,
            int consumerThreads,
            int itemsPerProducer,
            long consumerDelayMs) {
    }

    public record StartExperimentResponse(String jobId, String experimentType, String scenarioId) {
    }

    public record ExperimentJobResponse(
            String jobId,
            String experimentType,
            String scenarioId,
            ExperimentStatus status,
            long createdAtEpochMs,
            long updatedAtEpochMs,
            long startedAtEpochMs,
            long completedAtEpochMs,
            long firstMetricAtEpochMs,
            String failureMessage,
            Map<String, Long> counters,
            Map<String, Double> gauges,
            Map<String, String> details) {
    }

    public record ExperimentJobsResponse(List<ExperimentJobResponse> jobs) {
    }

    public record ExperimentMetricTimelineResponse(
            String jobId,
            String experimentType,
            String scenarioId,
            List<com.pnomeer.lab.core.MetricPoint> snapshots) {
    }
}
