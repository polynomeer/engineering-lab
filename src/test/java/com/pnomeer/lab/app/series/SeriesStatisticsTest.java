package com.pnomeer.lab.app.series;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SeriesStatisticsTest {
    @Test void summarizesObservedValuesWithNearestRankPercentile() {
        var stats = SeriesStatistics.of(List.of(10.0, 30.0, 20.0, 40.0));
        assertEquals(4, stats.count());
        assertEquals(10.0, stats.min());
        assertEquals(40.0, stats.max());
        assertEquals(25.0, stats.mean());
        assertEquals(25.0, stats.median());
        assertEquals(40.0, stats.p95()); // ceil(0.95 * 4) = 4th value: an observed run, not interpolated
        assertEquals(Math.sqrt(500.0 / 3), stats.standardDeviation(), 1e-9);
        assertEquals(Math.sqrt(500.0 / 3) / 25.0, stats.coefficientOfVariation(), 1e-9);
    }

    @Test void ignoresMissingValuesAndNeverInventsSpread() {
        var single = SeriesStatistics.of(Arrays.asList(null, 7.0, Double.NaN));
        assertEquals(1, single.count());
        assertEquals(7.0, single.median());
        assertNull(single.standardDeviation());
        assertNull(single.coefficientOfVariation());
        var empty = SeriesStatistics.of(List.of());
        assertEquals(0, empty.count());
        assertNull(empty.median());
        assertNull(SeriesStatistics.of(List.of(0.0, 0.0)).coefficientOfVariation());
    }

    @Test void separationIsConservativeAndNeedsThreeRepetitions() {
        var slow = SeriesStatistics.of(List.of(100.0, 110.0, 105.0));
        var fast = SeriesStatistics.of(List.of(300.0, 320.0, 310.0));
        var close = SeriesStatistics.of(List.of(108.0, 115.0, 120.0));
        assertEquals(SeriesStatistics.Separation.SEPARATED, SeriesStatistics.separation(slow, fast));
        assertEquals(SeriesStatistics.Separation.OVERLAPPING, SeriesStatistics.separation(slow, close));
        assertEquals(SeriesStatistics.Separation.INSUFFICIENT,
                SeriesStatistics.separation(slow, SeriesStatistics.of(List.of(300.0, 310.0))));
        assertEquals(200.0, SeriesStatistics.medianChange(slow, SeriesStatistics.of(List.of(315.0, 315.0, 315.0))), 1e-9);
        assertNull(SeriesStatistics.medianChange(SeriesStatistics.of(List.of(0.0)), fast));
        assertEquals(0.0, SeriesStatistics.medianChange(SeriesStatistics.of(List.of(0.0)), SeriesStatistics.of(List.of(0.0))));
    }
}
