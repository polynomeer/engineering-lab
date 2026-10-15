package com.pnomeer.lab.experiments.career;
class CreatorMysqlResourceRecoveryTest extends CreatorResourceRecoveryTest {String namespace(){return "creator-studio-mysql";}}
class CreatorRedisResourceRecoveryTest extends CreatorResourceRecoveryTest {String namespace(){return "creator-studio-redis";}}
abstract class CreatorResourceRecoveryTest extends EquityResourceRecoveryTest {
    @Override CareerContainerResources resources(){return new CreatorContainerResources(image,namespace().endsWith("redis"),docker::run);}
}
