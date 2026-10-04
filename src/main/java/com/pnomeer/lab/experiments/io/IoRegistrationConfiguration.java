package com.pnomeer.lab.experiments.io;

import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.scenario.*;
import org.springframework.context.annotation.*;

@Configuration
public class IoRegistrationConfiguration {
    @Bean
    public ExperimentRegistration<IoEndpointComparisonScenario> ioRegistration(IoEndpointComparisonExperiment experiment) {
        return new ExperimentRegistration<>(IoExperimentModule.DESCRIPTOR, definition -> experiment,
                (d, input) -> new IoEndpointComparisonScenario(d.scenario().id(), integer(d,"requests"),
                        integer(d,"delayMs"), (Boolean)d.parameters().get("pinning"), integer(d,"pinDelayMs")));
    }
    private static int integer(ScenarioDefinition d, String key) { return ((Number)d.parameters().get(key)).intValue(); }
}
