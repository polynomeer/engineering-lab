package com.pnomeer.lab.experiments.concurrency;

import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.scenario.*;
import org.springframework.context.annotation.*;

@Configuration
public class QueueRegistrationConfiguration {
    @Bean
    public ExperimentRegistration<QueueContentionScenario> queueRegistration(QueueContentionExperiment experiment) {
        return new ExperimentRegistration<>(QueueExperimentModule.DESCRIPTOR, definition -> experiment,
                (d, input) -> new QueueContentionScenario(d.scenario().id(), integer(d,"queueCapacity"),
                        integer(d,"producerThreads"), integer(d,"consumerThreads"),
                        integer(d,"itemsPerProducer"), integer(d,"consumerDelayMs")));
    }
    private static int integer(ScenarioDefinition d, String key) { return ((Number)d.parameters().get(key)).intValue(); }
}
