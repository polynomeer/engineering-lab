package com.pnomeer.lab.experiments.career;

import com.pnomeer.lab.runtime.DockerContainerEngine;
import java.util.Set;

public final class BatchExcelContainerResources extends CareerContainerResources {
    public BatchExcelContainerResources(String image) { this(image,DockerContainerEngine::command); }
    BatchExcelContainerResources(String image, Commands docker) {
        super(image,false,docker,"batch-excel-mysql",Set.of("career.batch-excel.batch-memory","career.batch-excel.excel-memory"),
                "batch_lab","batch-db","LAB_BATCH_");
    }
}
