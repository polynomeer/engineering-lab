package com.pnomeer.lab.app.series;

import com.pnomeer.lab.core.RunEnvironment;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BaselineFileTest {
    private static final RunEnvironment ENV = new RunEnvironment("25", "v", "vm", "Linux", "6", "amd64", 4, 1L << 30);

    private static BaselineFile file(List<Double> values) {
        return new BaselineFile(1, "concurrency.queue-contention", "s", "sha", "abc", 0, "commit", ENV,
                List.of(new BaselineFile.Measure("elapsed", "time.elapsedMs", "ms", "gauge", values)));
    }

    @Test void recomputesStatisticsFromTheRawValues() {
        var view = file(List.of(10.0, 12.0, 11.0)).view();
        assertEquals("file:abc", view.series().seriesId());
        assertEquals(3, view.measuredSucceeded());
        assertEquals(11.0, view.measures().getFirst().statistics().median());
    }

    @Test void namesEveryEnvironmentDifferenceAndNothingWhenEqual() {
        assertEquals(List.of(), file(List.of(1.0)).environmentDifferences(ENV));
        var other = new RunEnvironment("21", "v", "vm", "Mac OS X", "15", "aarch64", 8, 1L << 31);
        assertEquals(4, file(List.of(1.0)).environmentDifferences(other).size());
    }

    @Test void rejectsUnknownVersionsAndEmptyFiles() {
        assertThrows(IllegalArgumentException.class, () -> new BaselineFile(2, "e", "s", "sha", "abc", 0, null, ENV, file(List.of(1.0)).measures()));
        assertThrows(IllegalArgumentException.class, () -> new BaselineFile(1, "e", "s", "sha", "abc", 0, null, ENV, List.of()));
    }
}
