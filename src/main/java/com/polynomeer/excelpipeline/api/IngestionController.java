package com.polynomeer.excelpipeline.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartIngestionResponse startExcelIngestion(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file must not be empty");
        }
        return new StartIngestionResponse(ingestionService.startJob(file));
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
                state.getProducedCount(),
                state.getInsertedCount(),
                state.getValidationErrorCount(),
                state.getErrorSummary(),
                state.getFailureMessage());
    }

    public record StartIngestionResponse(String jobId) {
    }

    public record IngestionJobResponse(
            String jobId,
            IngestionJobStatus status,
            int producedCount,
            int insertedCount,
            int validationErrorCount,
            java.util.Map<String, Long> errorSummary,
            String failureMessage) {
    }
}
