package com.pnomeer.lab.app.io;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoLabControllerTest {
    @Test
    void returnsVirtualThreadEchoResponse() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            IoLabController controller = new IoLabController(executor);
            String response = controller.virtualThreadEcho("hello", 1, false, 1).get();
            assertTrue(response.contains("[virtual-thread] hello"));
        }
    }

    @Test
    void returnsPinnedVirtualThreadEchoResponse() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            IoLabController controller = new IoLabController(executor);
            String response = controller.virtualThreadEcho("hello", 1, true, 1).get();
            assertTrue(response.contains("pinning=true"));
        }
    }

    @Test
    void returnsReactiveEchoResponse() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            IoLabController controller = new IoLabController(executor);
            String response = controller.reactiveEcho("hello", 1).block();
            assertNotNull(response);
            assertTrue(response.contains("[reactive] hello"));
        }
    }

    @Test
    void returnsIoLabDashboardHtml() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            IoLabController controller = new IoLabController(executor);
            String html = controller.ioLabDashboard("hello");
            assertTrue(html.contains("IO Lab"));
            assertTrue(html.contains("/lab/io/virtual-thread/echo"));
            assertTrue(html.contains("/lab/io/reactive/echo"));
            assertTrue(html.contains("Pinning (0/1)"));
            assertTrue(html.contains("Run History"));
        }
    }
}
