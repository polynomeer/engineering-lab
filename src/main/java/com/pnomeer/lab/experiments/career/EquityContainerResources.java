package com.pnomeer.lab.experiments.career;

import com.pnomeer.lab.runtime.DockerContainerEngine;
import java.util.Set;

/** Keeps the deployed equity-v1 resource profiles and journals unchanged. */
public final class EquityContainerResources extends CareerContainerResources {
    public EquityContainerResources(String image, boolean redis) { this(image,redis,DockerContainerEngine::command); }
    EquityContainerResources(String image, boolean redis, Commands docker) {
        super(image,redis,docker,redis ? "equity-redis" : "equity-mysql",
                redis ? Set.of("career.equity-system.redis-lock") : Set.of("career.equity-system.deletion","career.equity-system.registration"),
                "equity_lab","equity-db","LAB_EQUITY_");
    }
}
