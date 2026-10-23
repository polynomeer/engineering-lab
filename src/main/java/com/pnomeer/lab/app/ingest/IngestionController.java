package com.pnomeer.lab.app.ingest;

import com.pnomeer.lab.experiments.pipeline.PipelineRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/ingest")
public class IngestionController {
    private final IngestionService ingestionService;
    private final JdbcTemplate jdbcTemplate;

    public IngestionController(IngestionService ingestionService, JdbcTemplate jdbcTemplate) {
        this.ingestionService = ingestionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartIngestionResponse startExcelIngestion(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "PIPELINE") PipelineRunner.RunMode mode) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file must not be empty");
        }
        return new StartIngestionResponse(ingestionService.startJob(file, mode), mode);
    }

    @GetMapping("/jobs/{jobId}")
    public IngestionJobResponse getJob(@PathVariable String jobId) {
        IngestionJobState state = ingestionService.getJob(jobId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found");
        }
        return new IngestionJobResponse(
                state.getJobId(),
                state.getStatus(),
                state.getExperimentType(),
                state.getScenarioId(),
                state.getFileName(),
                state.getRunMode(),
                state.getCreatedAtEpochMs(),
                state.getUpdatedAtEpochMs(),
                state.getStartedAtEpochMs(),
                state.getCompletedAtEpochMs(),
                state.getFirstProgressAtEpochMs(),
                state.getProducedCount(),
                state.getInsertedCount(),
                state.getValidationErrorCount(),
                state.getErrorSummary(),
                state.getFailureMessage(),
                state.isOomExists(),
                state.isDbDeadlock(),
                state.getLatestProgress(),
                state.getRunMetadata());
    }

    @GetMapping("/jobs")
    public IngestionJobsResponse listJobs() {
        var jobs = ingestionService.listJobs().stream()
                .map(state -> new IngestionJobResponse(
                        state.getJobId(),
                        state.getStatus(),
                        state.getExperimentType(),
                        state.getScenarioId(),
                        state.getFileName(),
                        state.getRunMode(),
                        state.getCreatedAtEpochMs(),
                        state.getUpdatedAtEpochMs(),
                        state.getStartedAtEpochMs(),
                        state.getCompletedAtEpochMs(),
                        state.getFirstProgressAtEpochMs(),
                        state.getProducedCount(),
                        state.getInsertedCount(),
                        state.getValidationErrorCount(),
                        state.getErrorSummary(),
                        state.getFailureMessage(),
                        state.isOomExists(),
                        state.isDbDeadlock(),
                        state.getLatestProgress(),
                        state.getRunMetadata()))
                .toList();
        return new IngestionJobsResponse(jobs);
    }

    @GetMapping("/jobs/{jobId}/timeline")
    public IngestionTimelineResponse getJobTimeline(@PathVariable String jobId) {
        IngestionJobState state = ingestionService.getJob(jobId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found");
        }
        return new IngestionTimelineResponse(state.getJobId(), state.getStatus(), state.getProgressTimeline());
    }

    @GetMapping("/jobs/{jobId}/metrics")
    public IngestionMetricTimelineResponse getJobMetricTimeline(@PathVariable String jobId) {
        IngestionJobState state = ingestionService.getJob(jobId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found");
        }
        return new IngestionMetricTimelineResponse(
                state.getJobId(),
                state.getExperimentType(),
                state.getScenarioId(),
                state.getMetricTimeline());
    }

    @GetMapping("/db/count")
    public DbCountResponse getDbCount() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from ingest_item", Integer.class);
        return new DbCountResponse(count == null ? 0 : count);
    }

    @GetMapping("/db/rows")
    public DbRowsResponse getDbRows(@RequestParam(defaultValue = "200") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 2000));
        var rows = jdbcTemplate.query(
                "select id, col1, col2, col3 from ingest_item order by id desc limit ?",
                (rs, rowNum) -> new DbRow(
                        rs.getLong("id"),
                        rs.getString("col1"),
                        rs.getString("col2"),
                        rs.getInt("col3")),
                safeLimit);
        return new DbRowsResponse(safeLimit, rows);
    }

    public record StartIngestionResponse(String jobId, PipelineRunner.RunMode mode) {
    }

    public record IngestionJobResponse(
            String jobId,
            IngestionJobStatus status,
            String experimentType,
            String scenarioId,
            String fileName,
            PipelineRunner.RunMode runMode,
            long createdAtEpochMs,
            long updatedAtEpochMs,
            long startedAtEpochMs,
            long completedAtEpochMs,
            long firstProgressAtEpochMs,
            int producedCount,
            int insertedCount,
            int validationErrorCount,
            java.util.Map<String, Long> errorSummary,
            String failureMessage,
            boolean oomExists,
            boolean dbDeadlock,
            com.pnomeer.lab.metrics.ExecutionMetricsSnapshot latestProgress,
            com.pnomeer.lab.core.RunMetadata runMetadata) {
    }

    public record IngestionJobsResponse(java.util.List<IngestionJobResponse> jobs) {
    }

    public record IngestionTimelineResponse(
            String jobId,
            IngestionJobStatus status,
            java.util.List<com.pnomeer.lab.metrics.ExecutionMetricsSnapshot> snapshots) {
    }

    public record IngestionMetricTimelineResponse(
            String jobId,
            String experimentType,
            String scenarioId,
            java.util.List<com.pnomeer.lab.core.MetricPoint> snapshots) {
    }

    public record DbCountResponse(int totalRows) {
    }

    public record DbRowsResponse(int limit, java.util.List<DbRow> rows) {
    }

    public record DbRow(long id, String col1, String col2, int col3) {
    }
}
