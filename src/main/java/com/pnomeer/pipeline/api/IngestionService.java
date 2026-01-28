package com.pnomeer.pipeline.api;

import com.pnomeer.pipeline.PipelineRunner;
import com.pnomeer.pipeline.model.ValidationError;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class IngestionService {
    private final PipelineRunner pipelineRunner;
    private final TaskExecutor taskExecutor;
    private final ConcurrentHashMap<String, IngestionJobState> jobs = new ConcurrentHashMap<>();

    public IngestionService(PipelineRunner pipelineRunner, @Qualifier("ingestTaskExecutor") TaskExecutor taskExecutor) {
        this.pipelineRunner = pipelineRunner;
        this.taskExecutor = taskExecutor;
    }

    public String startJob(MultipartFile file) {
        byte[] payload = readFile(file);
        String jobId = UUID.randomUUID().toString();
        IngestionJobState state = new IngestionJobState(jobId);
        jobs.put(jobId, state);

        taskExecutor.execute(() -> runJob(state, payload));
        return jobId;
    }

    public IngestionJobState getJob(String jobId) {
        return jobs.get(jobId);
    }

    private void runJob(IngestionJobState state, byte[] payload) {
        try {
            var result = pipelineRunner.run(new ByteArrayInputStream(payload));
            Map<String, Long> errorSummary = result.getValidationErrors().stream()
                    .map(ValidationError::getReason)
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            state.markSucceeded(
                    result.getProducedCount(),
                    result.getInsertedCount(),
                    result.getValidationErrors().size(),
                    errorSummary);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            state.markFailed("Job interrupted");
        } catch (Exception ex) {
            state.markFailed(ex.getMessage() == null ? "Pipeline failed" : ex.getMessage());
        }
    }

    private static byte[] readFile(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read upload", ex);
        }
    }
}
