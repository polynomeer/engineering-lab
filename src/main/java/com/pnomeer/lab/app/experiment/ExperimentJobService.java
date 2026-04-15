package com.pnomeer.lab.app.experiment;

import com.pnomeer.lab.core.ExperimentResult;
import com.pnomeer.lab.core.ExperimentRunner;
import com.pnomeer.lab.core.Experiment;
import com.pnomeer.lab.core.ExperimentScenario;
import com.pnomeer.lab.experiments.concurrency.QueueContentionExperiment;
import com.pnomeer.lab.experiments.concurrency.QueueContentionScenario;
import com.pnomeer.lab.experiments.io.IoEndpointComparisonExperiment;
import com.pnomeer.lab.experiments.io.IoEndpointComparisonScenario;
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
    private final IoEndpointComparisonExperiment ioEndpointComparisonExperiment;
    private final TaskExecutor taskExecutor;
    private final ConcurrentHashMap<String, ExperimentJobState> jobs = new ConcurrentHashMap<>();

    public ExperimentJobService(
            ExperimentRunner experimentRunner,
            QueueContentionExperiment queueContentionExperiment,
            IoEndpointComparisonExperiment ioEndpointComparisonExperiment,
            @Qualifier("experimentTaskExecutor") TaskExecutor taskExecutor) {
        this.experimentRunner = experimentRunner;
        this.queueContentionExperiment = queueContentionExperiment;
        this.ioEndpointComparisonExperiment = ioEndpointComparisonExperiment;
        this.taskExecutor = taskExecutor;
    }

    public String startQueueContentionJob(QueueContentionScenario scenario) {
        return startJob(queueContentionExperiment.type(), scenario.scenarioId(), state -> runJob(state, queueContentionExperiment, scenario));
    }

    public String startIoEndpointComparisonJob(IoEndpointComparisonScenario scenario) {
        return startJob(ioEndpointComparisonExperiment.type(), scenario.scenarioId(), state -> runJob(state, ioEndpointComparisonExperiment, scenario));
    }

    private String startJob(
            String experimentType,
            String scenarioId,
            java.util.function.Consumer<ExperimentJobState> task) {
        String jobId = UUID.randomUUID().toString();
        ExperimentJobState state = new ExperimentJobState(jobId, experimentType, scenarioId);
        jobs.put(jobId, state);
        taskExecutor.execute(() -> task.accept(state));
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

    private <C extends ExperimentScenario> void runJob(
            ExperimentJobState state,
            Experiment<C> experiment,
            C scenario) {
        state.markStarted();
        try {
            ExperimentResult result = experimentRunner.run(experiment, scenario);
            state.markCompleted(result);
        } catch (OutOfMemoryError error) {
            state.markFailed("Out of memory");
        } catch (Exception ex) {
            state.markFailed(ex.getMessage() == null ? "Experiment failed" : ex.getMessage());
        }
    }
}
