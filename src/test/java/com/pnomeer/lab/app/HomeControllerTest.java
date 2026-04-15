package com.pnomeer.lab.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeControllerTest {
    @Test
    void rendersIntegratedHomeDashboard() {
        HomeController controller = new HomeController();
        String html = controller.home();

        assertTrue(html.contains("Engineering Lab"));
        assertTrue(html.contains("/ingest/ui"));
        assertTrue(html.contains("/experiments/ui"));
        assertTrue(html.contains("/lab/io/ui"));
    }
}
