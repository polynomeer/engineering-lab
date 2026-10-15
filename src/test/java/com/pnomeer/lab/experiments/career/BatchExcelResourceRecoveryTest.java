package com.pnomeer.lab.experiments.career;

class BatchExcelResourceRecoveryTest extends EquityResourceRecoveryTest {
    String namespace(){return "batch-excel-mysql";}
    @Override CareerContainerResources resources(){return new BatchExcelContainerResources(image,docker::run);}
}
