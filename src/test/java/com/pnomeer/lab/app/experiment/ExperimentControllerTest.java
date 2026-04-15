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
        assertTrue(html.contains("comparisonChart"));
        assertTrue(html.contains("compareA"));
        assertTrue(html.contains("compareB"));
        assertTrue(html.contains("All experiments"));
    }
}
