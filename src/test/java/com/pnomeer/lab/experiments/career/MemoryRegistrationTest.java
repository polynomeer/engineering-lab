package com.pnomeer.lab.experiments.career;

import com.pnomeer.lab.scenario.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryRegistrationTest {
    @Test void rejectsUnknownExtraFractionalAndOutOfRangeInput() {
        var descriptor=MemoryRegistrationConfiguration.BATCH;
        var valid=new HashMap<String,Object>(Map.of("variant","AFTER","rows",2001,"seed",42));
        descriptor.validate(definition(valid));
        for(var entry:Map.<String,Object>of("rows",5001,"seed",-1,"variant","UNKNOWN","extra",true).entrySet()) {
            var invalid=new HashMap<>(valid); invalid.put(entry.getKey(),entry.getValue());
            assertThrows(ScenarioException.class,()->descriptor.validate(definition(invalid)));
        }
        var fractional=new HashMap<>(valid); fractional.put("rows",1.5);
        assertThrows(ScenarioException.class,()->descriptor.validate(definition(fractional)));
        MemoryRegistrationConfiguration.EXCEL.validate(new ScenarioDefinition(1,"career.batch-excel.excel-memory",
                new ScenarioDefinition.Scenario("test","Bounds"),valid,new ScenarioDefinition.Measurement("SINGLE_PASS"),null));
        valid.remove("seed"); assertThrows(ScenarioException.class,()->descriptor.validate(definition(valid)));
    }
    private ScenarioDefinition definition(Map<String,Object> parameters) {
        return new ScenarioDefinition(1,"career.batch-excel.batch-memory",new ScenarioDefinition.Scenario("test","Bounds"),parameters,
                new ScenarioDefinition.Measurement("SINGLE_PASS"),null);
    }
}
