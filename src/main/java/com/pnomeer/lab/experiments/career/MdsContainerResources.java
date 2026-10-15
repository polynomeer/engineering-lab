package com.pnomeer.lab.experiments.career;

import com.pnomeer.lab.runtime.DockerContainerEngine;
import java.util.Set;

public final class MdsContainerResources extends CareerContainerResources {
    public MdsContainerResources(String image) { this(image,DockerContainerEngine::command); }
    MdsContainerResources(String image, Commands docker) {
        super(image,false,docker,"mds-distribution-mysql",Set.of("career.mds-distribution.settlement-guard","career.mds-distribution.idempotency"),
                "mds_lab","mds-db","LAB_MDS_");
    }
}
