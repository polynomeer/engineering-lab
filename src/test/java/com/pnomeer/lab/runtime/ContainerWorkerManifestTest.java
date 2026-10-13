package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.storage.RunExecution;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ContainerWorkerManifestTest {
    @Test void requiresImmutableImageArtifactAndRegisteredExperiment() {
        assertThrows(IllegalArgumentException.class, () -> new ContainerWorkerManifest("worker:latest", "a".repeat(64), "module", Set.of("fixture")));
        assertThrows(IllegalArgumentException.class, () -> new ContainerWorkerManifest("sha256:" + "b".repeat(64), "bad", "module", Set.of("fixture")));
        assertThrows(IllegalArgumentException.class, () -> new ContainerWorkerManifest("sha256:" + "b".repeat(64), "a".repeat(64), "module", Set.of()));
    }
    @Test void containerEvidenceRequiresWorkerIdentityAndMeasurementScope() {
        assertThrows(IllegalArgumentException.class, () -> new RunExecution.Definition(RunExecution.Kind.CONTAINER, "module", 0, RunExecution.MeasurementScope.CONTROLLER_PROCESS));
        var definition = new RunExecution.Definition(RunExecution.Kind.CONTAINER, "module", 1, RunExecution.MeasurementScope.WORKER_PROCESS);
        assertThrows(IllegalArgumentException.class, () -> new RunExecution(definition, null, RunExecution.Phase.FINALIZING, true));
        assertFalse(new RunExecution(definition, null, RunExecution.Phase.PREPARING, false).evidenceComplete());
    }
}
