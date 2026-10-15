package com.pnomeer.lab.experiments.career;

class MdsResourceRecoveryTest extends EquityResourceRecoveryTest {
    String namespace(){return "mds-distribution-mysql";}
    @Override CareerContainerResources resources(){return new MdsContainerResources(image,docker::run);}
}
