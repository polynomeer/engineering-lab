package com.pnomeer.lab.app.series;

import com.pnomeer.lab.scenario.ScenarioDefinition;
import com.pnomeer.lab.scenario.ScenarioException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SweepGridTest {
    private final ScenarioDefinition base = new ScenarioDefinition(1, "concurrency.queue-contention",
            new ScenarioDefinition.Scenario("cap", "capacity"), Map.of("queueCapacity", 2, "producerThreads", 1),
            new ScenarioDefinition.Measurement("SINGLE_PASS"), null);

    @Test void expandsCombinationsInOdometerOrderWithSuffixedScenarioIds() {
        var grid = SweepGrid.normalize(new LinkedHashMap<>(Map.of("queueCapacity", List.of(1, 4))));
        grid = new LinkedHashMap<>(grid);
        grid.put("producerThreads", List.of(1, 2, 3));
        var points = SweepGrid.expand(base, grid, 3, 1);
        assertEquals(6, points.size());
        assertEquals(Map.of("queueCapacity", 1, "producerThreads", 2), points.get(1).overrides());
        assertEquals(Map.of("queueCapacity", 4, "producerThreads", 1), points.get(3).overrides());
        assertEquals("cap.p5", points.get(5).definition().scenario().id());
        assertEquals(4, points.get(5).definition().parameters().get("queueCapacity"));
        assertEquals("capacity", points.get(5).definition().scenario().hypothesis());
        assertEquals(List.of("queueCapacity", "producerThreads"), List.copyOf(points.get(0).overrides().keySet()));
    }

    @Test void rejectsMalformedGridsAndOversizedSweeps() {
        assertThrows(ScenarioException.class, () -> SweepGrid.normalize(Map.of()));
        assertThrows(ScenarioException.class, () -> SweepGrid.normalize(Map.of("queueCapacity", List.of())));
        assertThrows(ScenarioException.class, () -> SweepGrid.normalize(Map.of("queueCapacity", List.of(1, 1))));
        assertThrows(ScenarioException.class, () -> SweepGrid.normalize(Map.of("queueCapacity", List.of(1.5))));
        assertThrows(ScenarioException.class, () -> SweepGrid.normalize(Map.of("queueCapacity", 3)));
        var big = Map.<String, List<Object>>of("a", List.of(1, 2, 3, 4, 5), "b", List.of(1, 2, 3, 4, 5));
        assertThrows(ScenarioException.class, () -> SweepGrid.expand(base, big, 1, 0)); // 25 points
        var many = Map.<String, List<Object>>of("a", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
        assertThrows(ScenarioException.class, () -> SweepGrid.expand(base, many, 25, 1)); // 312 runs
        assertEquals(12, SweepGrid.expand(base, many, 20, 5).size()); // exactly 300 runs
    }
}
