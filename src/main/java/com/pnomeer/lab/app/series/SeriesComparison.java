package com.pnomeer.lab.app.series;

import java.util.*;

/** Measure-by-measure comparison of two series of the same experiment; never declares a winner. */
public record SeriesComparison(SeriesView baseline, SeriesView candidate, boolean sameDefinition, List<Row> measures) {
    public record Row(String role, String name, String unit, SeriesStatistics baseline, SeriesStatistics candidate,
            Double medianChangePct, SeriesStatistics.Separation separation) { }

    public static SeriesComparison of(SeriesView baseline, SeriesView candidate) {
        var candidates = new HashMap<String, SeriesView.Measure>();
        candidate.measures().forEach(m -> candidates.put(m.role(), m));
        var rows = new ArrayList<Row>();
        for (var b : baseline.measures()) {
            var c = candidates.get(b.role());
            // A role whose name or unit differs is a different quantity, not a comparable one.
            if (c == null || !c.name().equals(b.name()) || !c.unit().equals(b.unit())) continue;
            rows.add(new Row(b.role(), b.name(), b.unit(), b.statistics(), c.statistics(),
                    SeriesStatistics.medianChange(b.statistics(), c.statistics()), SeriesStatistics.separation(b.statistics(), c.statistics())));
        }
        return new SeriesComparison(baseline, candidate,
                baseline.series().definitionSha256().equals(candidate.series().definitionSha256()), rows);
    }
}
