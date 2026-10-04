package com.pnomeer.lab.experiments.io;

import com.pnomeer.lab.registry.*;
import java.util.*;
import static com.pnomeer.lab.registry.ParameterSpec.*;

/** Metadata only: usable by the offline CLI without a Spring context. */
public final class IoExperimentModule implements ExperimentModule {
    public static final ExperimentDescriptor DESCRIPTOR = new ExperimentDescriptor("io.endpoint-comparison", "IO endpoint comparison", "IO", "NONE",
            Map.of("requests", integer(1,10000,"requests"), "delayMs", integer(0,60000,"ms"),
                    "pinning", bool(), "pinDelayMs", integer(0,60000,"ms")), List.of("SINGLE_PASS"));

    @Override public ModuleDescriptor module() {
        return new ModuleDescriptor("io", "IO experiments", "engineering");
    }
    @Override public List<ExperimentDescriptor> experiments() { return List.of(DESCRIPTOR); }
}
