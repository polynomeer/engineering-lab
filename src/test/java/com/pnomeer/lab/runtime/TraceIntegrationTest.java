package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.core.storage.*;
import com.pnomeer.lab.storage.*;
import com.pnomeer.lab.app.run.RunController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TraceIntegrationTest {
    @TempDir Path root;
    @Test void persistsSuccessAndFailureSpansAndPagesMixedEvidenceAfterRestart() throws Exception {
        var options=new MetricWriterOptions(1,1,10,1,1,1000);
        try (var store=new JdbcRunStore(root,10,1);
             var runner=new DurableExperimentRunner(store,()->GitProvenance.unknown("test"),options,1,1,null,0,1,true,2)) {
            var experiment=new Experiment<ExperimentScenario>() {
                public String type() { return "test"; }
                public ExperimentSummary execute(ExperimentScenario s,java.util.function.Consumer<MetricPoint> sink) {
                    sink.accept(new MetricPoint(1,Map.of(),Map.of("count",1L),Map.of()));
                    if (s.scenarioId().equals("failed")) throw new IllegalStateException("expected");
                    return ExperimentSummary.empty();
                }
            };
            assertEquals(ExperimentStatus.SUCCEEDED,runner.run(experiment,()->"ok","ok").status());
            assertEquals(ExperimentStatus.FAILED,runner.run(experiment,()->"failed","failed").status());
        }
        try (var store=new JdbcRunStore(root,10,1)) {
            assertEquals("FAILED",store.recentMetrics("failed",1).getFirst().point().labels().get("status"));
            assertEquals(1L,ExperimentMetricPreview.read(store,"ok",1).getFirst().counters().get("count"));
            var properties=new StorageProperties(root.toString(),1,1,10,1,1,1000,10,1,1,".",100);
            var mvc=MockMvcBuilders.standaloneSetup(new RunController(store,properties)).build();
            mvc.perform(get("/runs/ok/traces").param("limit","1")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.metrics.length()").value(0)).andExpect(jsonPath("$.nextAfter").value(0));
            mvc.perform(get("/runs/ok/traces").param("limit","1").param("after","0")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.metrics[0].point.labels.stage").value("EXPERIMENT"));
            mvc.perform(get("/runs/missing/traces")).andExpect(status().isNotFound());
        }
    }
}
