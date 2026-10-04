package com.pnomeer.lab.experiments.concurrency;

import com.pnomeer.lab.registry.*;
import java.util.*;
import static com.pnomeer.lab.registry.ParameterSpec.*;

/** Metadata only: usable by the offline CLI without a Spring context. */
public final class QueueExperimentModule implements ExperimentModule {
    public static final ExperimentDescriptor DESCRIPTOR = new ExperimentDescriptor("concurrency.queue-contention", "Queue contention", "CONCURRENCY", "NONE",
            Map.of("queueCapacity", integer(1,100000,"items"), "producerThreads", integer(1,64,"threads"),
                    "consumerThreads", integer(1,64,"threads"), "itemsPerProducer", integer(1,1000000,"items"),
                    "consumerDelayMs", integer(0,60000,"ms")), List.of("SINGLE_PASS"));

    @Override public ModuleDescriptor module() {
        return new ModuleDescriptor("concurrency", "Queue contention", "engineering");
    }
    @Override public List<ExperimentDescriptor> experiments() { return List.of(DESCRIPTOR); }
}
