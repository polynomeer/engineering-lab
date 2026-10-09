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
    @Bean public ExperimentCatalog experimentCatalog(List<ExperimentModule> additionalModules) {
        var providers = new java.util.ArrayList<ExperimentModule>();
        java.util.ServiceLoader.load(ExperimentModule.class).forEach(providers::add);
        providers.addAll(additionalModules);
        var catalog = new ExperimentCatalog(providers);
        com.pnomeer.lab.metrics.MetricCatalog.including(catalog); // Reject conflicting metric ownership at startup.
        return catalog;
    }
    @Bean public ExperimentRegistry experimentRegistry(ExperimentCatalog catalog, List<ExperimentRegistration<?>> registrations) {
        catalog.verifyRegistrations(registrations);
        return new ExperimentRegistry(registrations);
    }
}
