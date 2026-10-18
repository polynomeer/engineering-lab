package com.pnomeer.lab.app.series;

import com.pnomeer.lab.app.series.RegressionCheck.*;
import com.pnomeer.lab.app.series.SeriesStatistics.Separation;
import com.pnomeer.lab.core.storage.RunSeries;
import com.pnomeer.lab.core.storage.RunStatus;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RegressionCheckTest {
    @Test void flagsOnlySeparatedChangesBeyondToleranceInTheWorseDirection() {
        assertEquals(Verdict.REGRESSION, RegressionCheck.verdict(Direction.LOWER_IS_BETTER, 12.0, Separation.SEPARATED, 5));
        assertEquals(Verdict.IMPROVEMENT, RegressionCheck.verdict(Direction.LOWER_IS_BETTER, -12.0, Separation.SEPARATED, 5));
        assertEquals(Verdict.REGRESSION, RegressionCheck.verdict(Direction.HIGHER_IS_BETTER, -12.0, Separation.SEPARATED, 5));
        assertEquals(Verdict.IMPROVEMENT, RegressionCheck.verdict(Direction.HIGHER_IS_BETTER, 12.0, Separation.SEPARATED, 5));
        assertEquals(Verdict.CHANGED, RegressionCheck.verdict(Direction.UNKNOWN, 12.0, Separation.SEPARATED, 5));
        assertEquals(Verdict.OK, RegressionCheck.verdict(Direction.LOWER_IS_BETTER, 4.0, Separation.SEPARATED, 5));
        assertEquals(Verdict.OK, RegressionCheck.verdict(Direction.LOWER_IS_BETTER, 40.0, Separation.OVERLAPPING, 5));
        assertEquals(Verdict.INSUFFICIENT, RegressionCheck.verdict(Direction.LOWER_IS_BETTER, 40.0, Separation.INSUFFICIENT, 5));
        assertEquals(Verdict.INSUFFICIENT, RegressionCheck.verdict(Direction.LOWER_IS_BETTER, null, Separation.SEPARATED, 5));
        assertEquals(Direction.HIGHER_IS_BETTER, RegressionCheck.direction("throughput"));
        assertEquals(Direction.UNKNOWN, RegressionCheck.direction("completed"));
    }

    private static SeriesView view(String id, double... values) {
        var series = new RunSeries(1, id, "queue", "s", "a".repeat(64), values.length, 0, RunStatus.SUCCEEDED, 1, 2, null);
        var list = java.util.Arrays.stream(values).boxed().toList();
        return new SeriesView(series, List.of(), values.length,
                List.of(new SeriesView.Measure("elapsed", "time.elapsedMs", "ms", "gauge", SeriesStatistics.of(list))));
    }

    @Test void overallFailsOnRegressionAndIsInconclusiveWithTooFewRepetitions() {
        var regressed = RegressionCheck.of(SeriesComparison.of(view("base", 100, 101, 102), view("cand", 130, 131, 132)), 5);
        assertEquals(Overall.REGRESSION, regressed.overall());
        assertEquals("base", regressed.baselineSeriesId());
        var stable = RegressionCheck.of(SeriesComparison.of(view("base", 100, 101, 102), view("cand", 101, 102, 103)), 5);
        assertEquals(Overall.PASS, stable.overall());
        var short_ = RegressionCheck.of(SeriesComparison.of(view("base", 100, 101, 102), view("cand", 130, 131)), 5);
        assertEquals(Overall.INSUFFICIENT, short_.overall());
        assertEquals(Overall.NO_BASELINE, RegressionCheck.noBaseline("cand", 5).overall());
        assertThrows(IllegalArgumentException.class, () -> RegressionCheck.of(SeriesComparison.of(view("a", 1, 2, 3), view("b", 1, 2, 3)), -1));
    }
}
