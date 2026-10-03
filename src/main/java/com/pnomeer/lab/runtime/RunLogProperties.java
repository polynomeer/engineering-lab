package com.pnomeer.lab.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab.logs")
public record RunLogProperties(boolean enabled, int maxEvents, long maxBytes) {
    public RunLogProperties {
        if (maxEvents < 2 || maxEvents > 10000 || maxBytes < 4096 || maxBytes > 1048576) {
            throw new IllegalArgumentException("Run logs require max-events 2..10000 and max-bytes 4096..1048576");
        }
    }
}
