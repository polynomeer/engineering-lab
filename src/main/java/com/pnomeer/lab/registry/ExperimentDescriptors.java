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
            Map.ofEntries(Map.entry("mode", choice("PIPELINE","SINGLE_THREAD")),
                    Map.entry("rawCapacity", integer(1,100000,"items")),
                    Map.entry("mappedCapacity", integer(1,100000,"items")),
                    Map.entry("validatorThreads", integer(1,64,"threads")),
                    Map.entry("inserterThreads", integer(1,64,"threads")),
                    Map.entry("chunkSize", integer(1,10000,"rows")),
                    Map.entry("offerTimeoutMs", integer(1,60000,"ms")),
                    Map.entry("flushIntervalMs", optionalInteger(1,60000,"ms",1000)),
                    Map.entry("maxAttempts", optionalInteger(1,10,"attempts",3)),
                    Map.entry("retryBackoffMs", optionalInteger(1,60000,"ms",100)),
                    Map.entry("errorPreviewLimit", optionalInteger(0,1000,"rows",100))), List.of("SINGLE_PASS"));
    public static List<ExperimentDescriptor> all() { return List.of(QUEUE, IO, PIPELINE); }
    public static ExperimentDescriptor get(String id) {
        return all().stream().filter(descriptor -> descriptor.id().equals(id)).findFirst()
                .orElseThrow(() -> new ScenarioException("Unknown experiment: " + id));
    }
}
