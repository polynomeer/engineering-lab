package com.pnomeer.lab.registry;

import com.pnomeer.lab.scenario.ScenarioException;
import java.util.*;
import static com.pnomeer.lab.registry.ParameterSpec.*;

/** The same explicit schemas are used by the HTTP registry and the offline CLI validator. */
public final class ExperimentDescriptors {
    private ExperimentDescriptors() { }
    public static final ExperimentDescriptor QUEUE = new ExperimentDescriptor("concurrency.queue-contention", "Queue contention", "CONCURRENCY", "NONE",
            Map.of("queueCapacity", integer(1,100000,"items"), "producerThreads", integer(1,64,"threads"),
                    "consumerThreads", integer(1,64,"threads"), "itemsPerProducer", integer(1,1000000,"items"),
                    "consumerDelayMs", integer(0,60000,"ms")), List.of("SINGLE_PASS"));
    public static final ExperimentDescriptor IO = new ExperimentDescriptor("io.endpoint-comparison", "IO endpoint comparison", "IO", "NONE",
            Map.of("requests", integer(1,10000,"requests"), "delayMs", integer(0,60000,"ms"),
                    "pinning", bool(), "pinDelayMs", integer(0,60000,"ms")), List.of("SINGLE_PASS"));
    public static final ExperimentDescriptor PIPELINE = new ExperimentDescriptor("pipeline.excel-ingestion", "Excel ingestion", "DATA", "XLSX_UPLOAD",
            Map.of("mode", choice("PIPELINE","SINGLE_THREAD"), "rawCapacity", integer(1,100000,"items"),
                    "mappedCapacity", integer(1,100000,"items"), "validatorThreads", integer(1,64,"threads"),
                    "inserterThreads", integer(1,64,"threads"), "chunkSize", integer(1,10000,"rows"),
                    "offerTimeoutMs", integer(1,60000,"ms")), List.of("SINGLE_PASS"));
    public static List<ExperimentDescriptor> all() { return List.of(QUEUE, IO, PIPELINE); }
    public static ExperimentDescriptor get(String id) {
        return all().stream().filter(descriptor -> descriptor.id().equals(id)).findFirst()
                .orElseThrow(() -> new ScenarioException("Unknown experiment: " + id));
    }
}
