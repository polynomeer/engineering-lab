package com.pnomeer.lab.experiments.career;

import com.pnomeer.lab.scenario.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EquityRegistrationTest {
    @Test void rejectsUnknownExtraFractionalAndOutOfRangeInput() {
        var descriptor=EquityRegistrationConfiguration.REGISTRATION;
        var valid=new HashMap<String,Object>(Map.of("variant","AFTER","rows",200,"chunkSize",20,"workers",2,"maxInFlight",2));
        descriptor.validate(definition(valid));
        for(var entry:Map.<String,Object>of("rows",10001,"chunkSize",0,"workers",1.5,"maxInFlight",17,"variant","UNKNOWN","extra",true).entrySet()) {
            var invalid=new HashMap<>(valid); invalid.put(entry.getKey(),entry.getValue());
            assertThrows(ScenarioException.class,()->descriptor.validate(definition(invalid)));
        }
        valid.remove("workers"); assertThrows(ScenarioException.class,()->descriptor.validate(definition(valid)));
    }
    private ScenarioDefinition definition(Map<String,Object> parameters) {
        return new ScenarioDefinition(1,"career.equity-system.registration",new ScenarioDefinition.Scenario("test","Bounds"),parameters,
                new ScenarioDefinition.Measurement("SINGLE_PASS"),null);
    }
}
