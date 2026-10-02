package com.pnomeer.lab.scenario;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab.scenario")
public record ScenarioProperties(int maxDocumentBytes, int maxInputBytes) {
    public ScenarioProperties {
        if (maxDocumentBytes < 1 || maxDocumentBytes > 1048576 || maxInputBytes < 1 || maxInputBytes > 52428800) {
            throw new IllegalArgumentException("Scenario limits must be within 1 MiB document / 50 MiB input");
        }
    }
}
