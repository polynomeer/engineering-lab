package com.pnomeer.lab.app.run;

import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.registry.pattern.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PatternCatalogControllerTest {
    @Test void exposesSeparateVersionedReadOnlyCatalogWithoutChangingModulePayload() throws Exception {
        var active = ExperimentCatalog.load();
        var patterns = new PatternCatalog(active, List.of(BuiltInPatternMetadata.contribution()));
        var mvc = MockMvcBuilders.standaloneSetup(new PatternCatalogController(patterns), new ModuleCatalogController(active)).build();
        mvc.perform(get("/dashboard/v1/pattern-catalog")).andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.categories.length()").value(7))
                .andExpect(jsonPath("$.patterns.length()").value(18))
                .andExpect(jsonPath("$.experiments.length()").value(3))
                .andExpect(jsonPath("$.experiments[0].experimentId").value("concurrency.queue-contention"))
                .andExpect(jsonPath("$.experiments[0].sourceModuleId").value("concurrency"))
                .andExpect(jsonPath("$.experiments[0].classification.categoryId").value("concurrency"))
                .andExpect(jsonPath("$.experiments[0].classification.patternIds[0]").value("bounded-queue"))
                .andExpect(jsonPath("$.experiments[0].readiness").doesNotExist());
        mvc.perform(post("/dashboard/v1/pattern-catalog")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/modules")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].module.collection").value("engineering"))
                .andExpect(jsonPath("$[0].experiments[0].classification").doesNotExist());
    }
}
