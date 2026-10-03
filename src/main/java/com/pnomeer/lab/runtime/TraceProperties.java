package com.pnomeer.lab.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab.trace")
public record TraceProperties(boolean enabled, int maxSpans) {
    public TraceProperties {
        if (maxSpans < 1 || maxSpans > 10000) throw new IllegalArgumentException("Trace max-spans must be 1..10000");
    }
}
