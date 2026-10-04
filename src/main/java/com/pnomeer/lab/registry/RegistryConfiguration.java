package com.pnomeer.lab.registry;

import com.pnomeer.lab.scenario.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import java.util.List;

@Configuration
@EnableConfigurationProperties(ScenarioProperties.class)
public class RegistryConfiguration {
    @Bean public ScenarioDocuments scenarioDocuments(ScenarioProperties properties) {
        return new ScenarioDocuments(properties.maxDocumentBytes());
    }
    @Bean public ExperimentCatalog experimentCatalog() {
        var catalog = ExperimentCatalog.load();
        com.pnomeer.lab.metrics.MetricCatalog.definitions(); // Reject conflicting metric ownership at startup.
        return catalog;
    }
    @Bean public ExperimentRegistry experimentRegistry(ExperimentCatalog catalog, List<ExperimentRegistration<?>> registrations) {
        catalog.verifyRegistrations(registrations);
        return new ExperimentRegistry(registrations);
    }
}
