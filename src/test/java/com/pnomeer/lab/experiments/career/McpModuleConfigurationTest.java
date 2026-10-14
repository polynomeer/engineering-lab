package com.pnomeer.lab.experiments.career;

import com.pnomeer.lab.registry.ExperimentCatalog;
import com.pnomeer.lab.scenario.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpModuleConfigurationTest {
    @Test void optInModulesPreserveSequenceAndAllowIndependentChecks() {
        var config = new McpModuleConfiguration();
        for (boolean sequence : List.of(false,true)) {
            var catalog = new ExperimentCatalog(List.of(config.mcpModule(sequence,true)));
            assertEquals(1,catalog.modules().size());
            assertEquals(sequence ? 3 : 2,catalog.experiments().size());
            assertEquals(sequence ? 3 : 0,catalog.metrics().size());
        }
        assertEquals(List.of(SequenceRegistrationConfiguration.DESCRIPTOR),config.mcpModule(true,false).experiments());
    }
    @Test void schemaRejectsUnknownOrExtraParameters() {
        for (var descriptor : List.of(ChecksRegistrationConfiguration.ARCHITECTURE,ChecksRegistrationConfiguration.VALIDATION)) {
            var variants = descriptor == ChecksRegistrationConfiguration.ARCHITECTURE
                    ? ChecksRegistrationConfiguration.ARCHITECTURE_VARIANTS : ChecksRegistrationConfiguration.VALIDATION_VARIANTS;
            for (String variant : variants) descriptor.validate(definition(descriptor.id(),Map.of("variant",variant),"SINGLE_PASS"));
            for (var parameters : List.<Map<String,Object>>of(Map.of(),Map.of("variant","UNKNOWN"),Map.of("variant",1),
                    Map.of("variant",variants.getFirst(),"workers",2)))
                assertThrows(ScenarioException.class,()->descriptor.validate(definition(descriptor.id(),parameters,"SINGLE_PASS")));
        }
    }
    private ScenarioDefinition definition(String id,Map<String,Object> parameters,String mode) {
        return new ScenarioDefinition(1,id,new ScenarioDefinition.Scenario("test","Checks"),parameters,new ScenarioDefinition.Measurement(mode),null);
    }
}
