package com.pnomeer.lab.app.maintenance;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deleting captures is opt-in; previews are always available. */
@ConfigurationProperties("lab.retention")
public record RetentionProperties(boolean applyEnabled) { }
