package com.pnomeer.lab.experiments.pipeline;

import com.pnomeer.lab.registry.*;
import java.util.*;
import static com.pnomeer.lab.registry.ParameterSpec.*;

/** Metadata only: usable by the offline CLI without a Spring context. */
public final class PipelineExperimentModule implements ExperimentModule {
    public static final ExperimentDescriptor DESCRIPTOR = new ExperimentDescriptor("pipeline.excel-ingestion", "Excel ingestion", "DATA", "XLSX_UPLOAD",
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
                    Map.entry("shutdownTimeoutMs", optionalInteger(1,60000,"ms",30000)),
                    Map.entry("errorPreviewLimit", optionalInteger(0,1000,"rows",100))), List.of("SINGLE_PASS"));

    @Override public ModuleDescriptor module() {
        return new ModuleDescriptor("pipeline", "Excel pipeline", "engineering");
    }
    @Override public List<ExperimentDescriptor> experiments() { return List.of(DESCRIPTOR); }
}
