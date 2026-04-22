package com.pnomeer.lab.app.experiment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentControllerTest {
    @Test
    void rendersDashboardWithFilterAndComparisonView() {
        ExperimentController controller = new ExperimentController(null);

        String html = controller.getExperimentDashboard();

        assertTrue(html.contains("Experiment Type Filter"));
        assertTrue(html.contains("Comparison View"));
        assertTrue(html.contains("Timeline Overlay"));
        assertTrue(html.contains("Quick Demo"));
        assertTrue(html.contains("Backpressure Stress"));
        assertTrue(html.contains("Pinned Virtual Thread"));
        assertTrue(html.contains("comparisonChart"));
        assertTrue(html.contains("Export JSON"));
        assertTrue(html.contains("Export CSV"));
        assertTrue(html.contains("Recent Preset History"));
        assertTrue(html.contains("presetHistoryRows"));
        assertTrue(html.contains("data-history-id"));
        assertTrue(html.contains("Restored history entry"));
        assertTrue(html.contains("compareA"));
        assertTrue(html.contains("compareB"));
        assertTrue(html.contains("All experiments"));
        assertTrue(html.contains("engineering-lab.experiments.dashboard"));
    }
}
