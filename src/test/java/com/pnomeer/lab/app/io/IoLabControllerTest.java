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
            String response = controller.virtualThreadEcho("hello", 1).get();
            assertTrue(response.contains("[virtual-thread] hello"));
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
}
