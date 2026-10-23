package com.pnomeer.lab.app.series;

import java.util.*;

/**
 * Candidate series against a pinned baseline. A measure is flagged only when its median moves
 * beyond the tolerance and the observed ranges do not overlap, so noise alone never fails a check.
 */
public record RegressionCheck(Overall overall, String baselineSeriesId, String candidateSeriesId, double tolerancePct, List<Row> measures,
        List<String> cautions) {
    public enum Overall { PASS, REGRESSION, INSUFFICIENT, NO_BASELINE }
    public enum Direction { LOWER_IS_BETTER, HIGHER_IS_BETTER, UNKNOWN }
    public enum Verdict { OK, IMPROVEMENT, REGRESSION, CHANGED, INSUFFICIENT }
    public record Row(String role, String name, String unit, Direction direction, Double baselineMedian, Double candidateMedian,
            Double changePct, SeriesStatistics.Separation separation, Verdict verdict) { }

    /** Contracts do not declare a direction; only these well-known roles get one. */
    private static final Map<String, Direction> DIRECTIONS = Map.of(
            "elapsed", Direction.LOWER_IS_BETTER,
            "throughput", Direction.HIGHER_IS_BETTER,
            "heap", Direction.LOWER_IS_BETTER);
    public static final double DEFAULT_TOLERANCE_PCT = 5.0;

    public static Direction direction(String role) { return DIRECTIONS.getOrDefault(role, Direction.UNKNOWN); }

    public static RegressionCheck noBaseline(String candidate, double tolerancePct) {
        return new RegressionCheck(Overall.NO_BASELINE, null, candidate, tolerancePct, List.of(), List.of());
    }

    public static RegressionCheck of(SeriesComparison comparison, double tolerancePct) {
        if (!(tolerancePct >= 0 && tolerancePct <= 100)) throw new IllegalArgumentException("tolerancePct must be 0..100");
        var rows = new ArrayList<Row>();
        for (var m : comparison.measures()) {
            var direction = direction(m.role());
            rows.add(new Row(m.role(), m.name(), m.unit(), direction, m.baseline().median(), m.candidate().median(),
                    m.medianChangePct(), m.separation(), verdict(direction, m.medianChangePct(), m.separation(), tolerancePct)));
        }
        Overall overall;
        if (rows.isEmpty() || rows.stream().anyMatch(r -> r.verdict() == Verdict.INSUFFICIENT)) overall = Overall.INSUFFICIENT;
        else if (rows.stream().anyMatch(r -> r.verdict() == Verdict.REGRESSION || r.verdict() == Verdict.CHANGED)) overall = Overall.REGRESSION;
        else overall = Overall.PASS;
        return new RegressionCheck(overall, comparison.baseline().series().seriesId(), comparison.candidate().series().seriesId(), tolerancePct, rows, List.of());
    }

    public RegressionCheck withCautions(List<String> cautions) {
        return new RegressionCheck(overall, baselineSeriesId, candidateSeriesId, tolerancePct, measures, List.copyOf(cautions));
    }

    static Verdict verdict(Direction direction, Double changePct, SeriesStatistics.Separation separation, double tolerancePct) {
        if (separation == SeriesStatistics.Separation.INSUFFICIENT || changePct == null) return Verdict.INSUFFICIENT;
        if (separation == SeriesStatistics.Separation.OVERLAPPING || Math.abs(changePct) <= tolerancePct) return Verdict.OK;
        return switch (direction) {
            case LOWER_IS_BETTER -> changePct > 0 ? Verdict.REGRESSION : Verdict.IMPROVEMENT;
            case HIGHER_IS_BETTER -> changePct < 0 ? Verdict.REGRESSION : Verdict.IMPROVEMENT;
            case UNKNOWN -> Verdict.CHANGED;
        };
    }
}
