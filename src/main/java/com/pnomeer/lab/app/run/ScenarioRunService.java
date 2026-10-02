package com.pnomeer.lab.app.run;

import com.pnomeer.lab.core.ExperimentScenario;
import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.scenario.*;
import com.pnomeer.lab.runtime.DurableExperimentRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Service
public class ScenarioRunService {
    private final ExperimentRegistry registry;
    private final ScenarioDocuments documents;
    private final DurableExperimentRunner runner;
    private final TaskExecutor experimentExecutor, ingestExecutor;
    public ScenarioRunService(ExperimentRegistry registry, ScenarioDocuments documents, DurableExperimentRunner runner,
            @Qualifier("experimentTaskExecutor") TaskExecutor experimentExecutor,
            @Qualifier("ingestTaskExecutor") TaskExecutor ingestExecutor) {
        this.registry = registry; this.documents = documents; this.runner = runner;
        this.experimentExecutor = experimentExecutor; this.ingestExecutor = ingestExecutor;
    }
    public String submit(ScenarioDefinition definition, byte[] input) {
        var invocation = registry.bind(definition, input, documents);
        TaskExecutor executor = definition.input() == null ? experimentExecutor : ingestExecutor;
        return submit(invocation, executor);
    }
    private <C extends ExperimentScenario> String submit(ExperimentInvocation<C> invocation, TaskExecutor executor) {
        var run = runner.prepare(invocation.experiment(), invocation.scenario(), UUID.randomUUID().toString());
        try { executor.execute(() -> runner.runPrepared(invocation.experiment(), invocation.scenario(), run)); }
        catch (TaskRejectedException ex) {
            runner.reject(run, "Executor rejected the scenario run");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Experiment queue is full or shutting down", ex);
        }
        return run.runId();
    }
}
