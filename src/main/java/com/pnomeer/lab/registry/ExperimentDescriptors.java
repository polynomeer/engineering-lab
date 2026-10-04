package com.pnomeer.lab.registry;

import com.pnomeer.lab.experiments.concurrency.QueueExperimentModule;
import com.pnomeer.lab.experiments.io.IoExperimentModule;
import com.pnomeer.lab.experiments.pipeline.PipelineExperimentModule;
import java.util.List;

/** Compatibility aliases. New modules contribute through ExperimentModule, not this class. */
public final class ExperimentDescriptors {
    private ExperimentDescriptors() { }
    public static final ExperimentDescriptor QUEUE = QueueExperimentModule.DESCRIPTOR;
    public static final ExperimentDescriptor IO = IoExperimentModule.DESCRIPTOR;
    public static final ExperimentDescriptor PIPELINE = PipelineExperimentModule.DESCRIPTOR;
    public static List<ExperimentDescriptor> all() { return ExperimentCatalog.load().experiments(); }
    public static ExperimentDescriptor get(String id) { return ExperimentCatalog.load().get(id); }
}
