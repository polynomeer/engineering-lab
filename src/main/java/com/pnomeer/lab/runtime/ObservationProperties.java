package com.pnomeer.lab.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab.observation")
public record ObservationProperties(boolean jvmEnabled) { }
