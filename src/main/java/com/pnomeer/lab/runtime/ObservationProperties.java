package com.pnomeer.lab.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab.observation")
public record ObservationProperties(boolean jvmEnabled, long jvmIntervalMs, int maxPeriodicSamples) {
    public ObservationProperties {
        if ((jvmIntervalMs != 0 && (jvmIntervalMs < 100 || jvmIntervalMs > 60000))
                || maxPeriodicSamples < 1 || maxPeriodicSamples > 10000) {
            throw new IllegalArgumentException("JVM interval must be 0 or 100..60000 ms; sample cap must be 1..10000");
        }
    }
}
