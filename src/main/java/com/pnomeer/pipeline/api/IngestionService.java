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
import java.util.List;
import java.util.Comparator;
import java.util.Locale;

@Service
public class IngestionService {
    private final PipelineRunner pipelineRunner;
    private final TaskExecutor taskExecutor;
    private final ConcurrentHashMap<String, IngestionJobState> jobs = new ConcurrentHashMap<>();

    public IngestionService(PipelineRunner pipelineRunner, @Qualifier("ingestTaskExecutor") TaskExecutor taskExecutor) {
        this.pipelineRunner = pipelineRunner;
        this.taskExecutor = taskExecutor;
    }

    public String startJob(MultipartFile file, PipelineRunner.RunMode runMode) {
        byte[] payload = readFile(file);
        String jobId = UUID.randomUUID().toString();
        String fileName = file.getOriginalFilename() == null ? "unknown.xlsx" : file.getOriginalFilename();
        IngestionJobState state = new IngestionJobState(jobId, fileName, runMode);
        jobs.put(jobId, state);

        taskExecutor.execute(() -> runJob(state, payload));
        return jobId;
    }

    public IngestionJobState getJob(String jobId) {
        return jobs.get(jobId);
    }

    public List<IngestionJobState> listJobs() {
        return jobs.values().stream()
                .sorted(Comparator.comparingLong(IngestionJobState::getCreatedAtEpochMs).reversed())
                .toList();
    }

    private void runJob(IngestionJobState state, byte[] payload) {
        state.markStarted();
        try {
            var result = pipelineRunner.run(new ByteArrayInputStream(payload), state.getRunMode(), state::recordProgress);
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
            state.markFailed("Job interrupted", false, false);
        } catch (OutOfMemoryError error) {
            state.markFailed("Out of memory", true, false);
        } catch (Exception ex) {
            state.markFailed(
                    ex.getMessage() == null ? "Pipeline failed" : ex.getMessage(),
                    containsOutOfMemory(ex),
                    containsDeadlock(ex));
        }
    }

    private static boolean containsOutOfMemory(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof OutOfMemoryError) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("outofmemory")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsDeadlock(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("deadlock") || lower.contains("sqlstate 40001")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static byte[] readFile(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read upload", ex);
        }
    }
}
