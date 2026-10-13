package com.pnomeer.lab.core.storage;

import com.pnomeer.lab.core.GitProvenance;
import com.pnomeer.lab.core.RunEnvironment;
import java.util.Objects;

/** Additive execution evidence; never replaces the controller's RunMetadata. */
public record RunExecution(Definition definition, Worker worker, Phase phase, boolean evidenceComplete) {
    public enum Kind { IN_PROCESS, LOCAL_JVM, CONTAINER }
    public enum Phase { PREPARING, MEASURING, FINALIZING }
    public enum MeasurementScope { CONTROLLER_PROCESS, WORKER_PROCESS }
    public static final int MAX_BYTES = 65536;

    public record Definition(Kind kind, String moduleId, int protocolVersion, MeasurementScope measurementScope) {
        public Definition {
            Objects.requireNonNull(kind); Objects.requireNonNull(measurementScope);
            if (moduleId == null || !moduleId.matches("[a-zA-Z0-9._-]{1,100}")) {
                throw new IllegalArgumentException("Invalid execution module ID");
            }
            if (kind == Kind.IN_PROCESS && (protocolVersion != 0 || measurementScope != MeasurementScope.CONTROLLER_PROCESS)
                    || kind != Kind.IN_PROCESS && (protocolVersion < 1 || measurementScope != MeasurementScope.WORKER_PROCESS)) {
                throw new IllegalArgumentException("Execution kind, protocol and measurement scope disagree");
            }
        }
    }

    /** Actual facts received at handshake, immutable once registered. Null worker means unknown. */
    public record Worker(RunEnvironment environment, GitProvenance git, String artifactSha256) {
        public Worker {
            Objects.requireNonNull(environment); Objects.requireNonNull(git);
            for (String field : new String[] {environment.javaVersion(), environment.javaVendor(), environment.vmName(),
                    environment.osName(), environment.osVersion(), environment.osArch()}) {
                if (field == null || field.isBlank() || field.length() > 256) throw new IllegalArgumentException("Invalid worker environment field");
            }
            if (environment.availableProcessors() < 1 || environment.maxHeapBytes() < 1
                    || git.commit() != null && git.commit().length() > 128
                    || git.error() != null && git.error().length() > 1024) {
                throw new IllegalArgumentException("Invalid worker runtime or provenance bounds");
            }
            if (artifactSha256 == null || !artifactSha256.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid worker artifact SHA-256");
            }
        }
    }

    public RunExecution {
        Objects.requireNonNull(definition); Objects.requireNonNull(phase);
        if (definition.kind() == Kind.IN_PROCESS && worker != null) {
            throw new IllegalArgumentException("In-process execution cannot have worker identity");
        }
        if (evidenceComplete && (phase != Phase.FINALIZING || definition.kind() != Kind.IN_PROCESS && worker == null)) {
            throw new IllegalArgumentException("Complete evidence requires finalization and worker identity when applicable");
        }
    }
}
