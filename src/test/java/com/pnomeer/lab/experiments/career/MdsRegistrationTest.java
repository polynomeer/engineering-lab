package com.pnomeer.lab.experiments.career;
import com.pnomeer.lab.scenario.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MdsRegistrationTest {
    @Test void validatesBothCatalogsAndRejectsUnknownMissingAndExtraInput(){
        for(var descriptor:List.of(MdsRegistrationConfiguration.GUARD,MdsRegistrationConfiguration.IDEMPOTENCY)){
            for(String variant:descriptor.parameters().get("variant").values())descriptor.validate(definition(descriptor.id(),Map.of("variant",variant)));
            for(var invalid:List.<Map<String,Object>>of(Map.of(),Map.of("variant","UNKNOWN"),Map.of("variant",1),Map.of("variant",descriptor.parameters().get("variant").values().getFirst(),"rows",1)))
                assertThrows(ScenarioException.class,()->descriptor.validate(definition(descriptor.id(),invalid)));
        }
    }
    private ScenarioDefinition definition(String id,Map<String,Object> parameters){return new ScenarioDefinition(1,id,new ScenarioDefinition.Scenario("test","bounds"),parameters,new ScenarioDefinition.Measurement("SINGLE_PASS"),null);}
}
