package com.pnomeer.lab.registry;

import java.util.List;

/** Trusted, build-time metadata provider shared by the server and offline CLI. */
public interface ExperimentModule {
    ModuleDescriptor module();
    List<ExperimentDescriptor> experiments();
    default List<com.pnomeer.lab.metrics.MetricCatalog.Definition> metrics() { return List.of(); }
}
