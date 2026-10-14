package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.storage.RunExecution;
import java.util.*;
import java.util.function.Supplier;

/** Common server-owned routing and capability contract; recovery policies stay deployment-specific. */
public interface WorkerRegistration {
    default boolean producesMetrics() { return true; }
    String id();
    String moduleId();
    Set<String> experiments();
    RunExecution.Kind kind();
    ExperimentExecutor executor();
    Supplier<LocalJvmRegistration.Readiness> readiness();
    List<String> variants();
}
