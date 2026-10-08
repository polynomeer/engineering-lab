package com.pnomeer.lab.app.run;

import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.runtime.*;
import com.pnomeer.lab.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DashboardCatalogControllerTest {
    private DashboardCatalogController controller(ExperimentCatalog catalog, boolean enabled) {
        return new DashboardCatalogController(catalog, new StorageProperties("unused", 2, 2, 10, 2, 1, 1000, 2, 2, 2, ".", 100),
                new ObservationProperties(enabled, 0, 10), new TraceProperties(enabled, 10),
                new JfrProperties(enabled, 10, 1048576), new RunLogProperties(enabled, 10, 4096));
    }
    @Test void reportsActualConfigurationAndDoesNotAdvertiseUnimplementedWorkers() throws Exception {
        for (boolean enabled : List.of(false, true)) {
            var mvc = MockMvcBuilders.standaloneSetup(controller(ExperimentCatalog.load(), enabled)).build();
            mvc.perform(get("/dashboard/v1/capabilities")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.schemaVersion").value(1))
                    .andExpect(jsonPath("$.features.executionEvidence").value(true))
                    .andExpect(jsonPath("$.features.localJvm").value(false))
                    .andExpect(jsonPath("$.features.cancellation").value(false))
                    .andExpect(jsonPath("$.features.jfr").value(enabled))
                    .andExpect(jsonPath("$.features.trace").value(enabled))
                    .andExpect(jsonPath("$.features.jvmObservation").value(enabled))
                    .andExpect(jsonPath("$.features.runLogs").value(enabled))
                    .andExpect(jsonPath("$.workerProtocolVersions.length()").value(0))
                    .andExpect(jsonPath("$.limits.checkPageSize").value(2));
            mvc.perform(get("/dashboard/v1/experiments/pipeline.excel-ingestion")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.capabilities.fileUpload").value(true))
                    .andExpect(jsonPath("$.capabilities.checksProduced").value(false));
            mvc.perform(get("/dashboard/v1/experiments/missing")).andExpect(status().isNotFound());
        }
    }
    @Test void discoversNewModuleThroughCatalogWithoutDuplicatingModuleLists() throws Exception {
        var provider = new ExperimentModule() {
            public ModuleDescriptor module() { return new ModuleDescriptor("new-module", "New module", "engineering"); }
            public List<ExperimentDescriptor> experiments() {
                return List.of(new ExperimentDescriptor("new.experiment", "New experiment", "TEST", "NONE", Map.of(), List.of("SINGLE_PASS")));
            }
        };
        var catalog = new ExperimentCatalog(List.of(provider));
        var mvc = MockMvcBuilders.standaloneSetup(controller(catalog, false), new ModuleCatalogController(catalog)).build();
        mvc.perform(get("/dashboard/v1/modules")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].module.id").value("new-module"))
                .andExpect(jsonPath("$[0].readiness").value("READY"))
                .andExpect(jsonPath("$[0].experimentIds[0]").value("new.experiment"));
        mvc.perform(get("/dashboard/v1/experiments/new.experiment")).andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleId").value("new-module"))
                .andExpect(jsonPath("$.descriptorUrl").value("/experiments/new.experiment"))
                .andExpect(jsonPath("$.variants.length()").value(0));
        mvc.perform(get("/modules")).andExpect(status().isOk()).andExpect(jsonPath("$[0].experiments[0].id").value("new.experiment"));
    }
}
