package com.pnomeer.lab.app.run;

import com.pnomeer.lab.experiments.career.SequenceRegistrationConfiguration;
import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.runtime.*;
import com.pnomeer.lab.storage.StorageProperties;
import com.pnomeer.lab.worker.WorkerManifest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class DashboardWorkerCatalogTest {
    @Test void exposesReadinessAndOnlyWorkerSupportedObservations() throws Exception {
        var state = new AtomicReference<>(new LocalJvmRegistration.Readiness("DOCKER_UNAVAILABLE", "WORKER_DOCKER_NOT_READY"));
        var provider = new com.pnomeer.lab.experiments.career.McpModuleConfiguration().mcpModule(true, false);
        var catalog = new ExperimentCatalog(List.of(provider));
        Path path = Path.of(".").toAbsolutePath();
        var manifest = new WorkerManifest(path.resolve("java"), path.resolve("worker.jar"), "a".repeat(64),
                "mcp-platform", Set.of(SequenceRegistrationConfiguration.ID), 256);
        var worker = new LocalJvmRegistration("sequence", manifest, path, path, Set.of(), ignored -> null, state::get,
                SequenceRegistrationConfiguration.VARIANTS);
        var controller = new DashboardCatalogController(catalog,
                new StorageProperties("unused",2,2,10,2,1,1000,2,2,2,".",100),
                new ObservationProperties(true,0,10), new TraceProperties(true,10),
                new JfrProperties(true,10,1048576), new RunLogProperties(true,10,4096), List.of(worker));
        var mvc = MockMvcBuilders.standaloneSetup(controller, new MetricCatalogController(catalog)).build();
        mvc.perform(get("/dashboard/v1/modules")).andExpect(jsonPath("$[0].readiness").value("DOCKER_UNAVAILABLE"));
        mvc.perform(get("/dashboard/v1/experiments/" + SequenceRegistrationConfiguration.ID))
                .andExpect(jsonPath("$.executionKind").value("LOCAL_JVM"))
                .andExpect(jsonPath("$.readiness").value("DOCKER_UNAVAILABLE"))
                .andExpect(jsonPath("$.variants.length()").value(3))
                .andExpect(jsonPath("$.capabilities.checksProduced").value(true))
                .andExpect(jsonPath("$.capabilities.jvmObservation").value(false))
                .andExpect(jsonPath("$.capabilities.jfr").value(false))
                .andExpect(jsonPath("$.capabilities.runLogs").value(false));
        state.set(new LocalJvmRegistration.Readiness("READY", null));
        mvc.perform(get("/dashboard/v1/modules")).andExpect(jsonPath("$[0].readiness").value("READY"));
        mvc.perform(get("/dashboard/v1/capabilities")).andExpect(jsonPath("$.workerProtocolVersions[0]").value(1));
        assertEquals("ns", com.pnomeer.lab.metrics.MetricCatalog.including(catalog).get("sequence.elapsedNanos").unit());
    }
    @Test void unavailableWorkerSubmissionReturns503BeforeScheduling() throws Exception {
        var documents = new com.pnomeer.lab.scenario.ScenarioDocuments(65536);
        var registry = new ExperimentRegistry(List.of(new SequenceRegistrationConfiguration().sequenceRegistration(documents)));
        var runner = org.mockito.Mockito.mock(DurableExperimentRunner.class);
        org.mockito.Mockito.doThrow(new LocalJvmRegistration.WorkerUnavailableException("DOCKER_UNAVAILABLE"))
                .when(runner).prepare(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        org.springframework.core.task.TaskExecutor tasks = ignored -> { throw new AssertionError("Unavailable worker must not be scheduled"); };
        var service = new ScenarioRunService(registry, documents, runner, tasks, tasks);
        var controller = new ScenarioRunController(service, registry, documents,
                new com.pnomeer.lab.scenario.ScenarioProperties(65536, 52428800));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/runs").contentType("application/json").content(java.nio.file.Files.readString(
                Path.of("experiments/career-sequence/scenarios/atomic-update.json"))))
                .andExpect(status().isServiceUnavailable());
    }
}
