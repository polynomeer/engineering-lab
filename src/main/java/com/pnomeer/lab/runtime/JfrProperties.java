package com.pnomeer.lab.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab.jfr")
public record JfrProperties(boolean enabled, int durationSeconds, long maxBytes) {
    public JfrProperties {
        if (durationSeconds < 1 || durationSeconds > 3600 || maxBytes < 1024 * 1024 || maxBytes > 256L * 1024 * 1024) {
            throw new IllegalArgumentException("JFR requires duration-seconds 1..3600 and max-bytes 1..256 MiB");
        }
    }
}
