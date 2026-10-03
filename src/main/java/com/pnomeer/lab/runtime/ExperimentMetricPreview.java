package com.pnomeer.lab.runtime;

import com.pnomeer.lab.core.MetricPoint;
import com.pnomeer.lab.core.storage.RunStore;
import java.util.*;

/** Legacy dashboards expect experiment observations only, not process boundary samples. */
public final class ExperimentMetricPreview {
    private ExperimentMetricPreview() { }
    public static List<MetricPoint> read(RunStore store, String id, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        var result = new ArrayList<MetricPoint>(limit);
        long before = Long.MAX_VALUE;
        while (result.size() < limit) {
            var page = store.metricsBefore(id, before, limit);
            if (page.isEmpty()) break;
            for (var metric : page) {
                before = metric.sequence();
                if (!java.util.Set.of("jvm.boundary", "jvm.periodic", "trace.span").contains(metric.point().labels().getOrDefault("source", ""))) result.add(metric.point());
                if (result.size() == limit) break;
            }
            if (page.size() < limit) break;
        }
        return List.copyOf(result.reversed());
    }
}
