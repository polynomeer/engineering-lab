package com.pnomeer.lab.app.experiment;

import com.pnomeer.lab.core.ExperimentResult;
import com.pnomeer.lab.core.ExperimentRunner;
import com.pnomeer.lab.experiments.concurrency.QueueContentionExperiment;
import com.pnomeer.lab.experiments.concurrency.QueueContentionScenario;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ExperimentJobService {
    private final ExperimentRunner experimentRunner;
    private final QueueContentionExperiment queueContentionExperiment;
    private final TaskExecutor taskExecutor;
    private final ConcurrentHashMap<String, ExperimentJobState> jobs = new ConcurrentHashMap<>();

    public ExperimentJobService(
            ExperimentRunner experimentRunner,
            QueueContentionExperiment queueContentionExperiment,
            @Qualifier("experimentTaskExecutor") TaskExecutor taskExecutor) {
        this.experimentRunner = experimentRunner;
        this.queueContentionExperiment = queueContentionExperiment;
        this.taskExecutor = taskExecutor;
    }

    public String startQueueContentionJob(QueueContentionScenario scenario) {
        String jobId = UUID.randomUUID().toString();
        ExperimentJobState state = new ExperimentJobState(jobId, queueContentionExperiment.type(), scenario.scenarioId());
        jobs.put(jobId, state);
        taskExecutor.execute(() -> runQueueContentionJob(state, scenario));
        return jobId;
    }

    public ExperimentJobState getJob(String jobId) {
        return jobs.get(jobId);
    }

    public List<ExperimentJobState> listJobs() {
        return jobs.values().stream()
                .sorted(Comparator.comparingLong(ExperimentJobState::getCreatedAtEpochMs).reversed())
                .toList();
    }

    private void runQueueContentionJob(ExperimentJobState state, QueueContentionScenario scenario) {
        state.markStarted();
        try {
            ExperimentResult result = experimentRunner.run(queueContentionExperiment, scenario);
            state.markCompleted(result);
        } catch (OutOfMemoryError error) {
            state.markFailed("Out of memory");
        } catch (Exception ex) {
            state.markFailed(ex.getMessage() == null ? "Experiment failed" : ex.getMessage());
        }
    }
}
