package com.pnomeer.lab.core.storage;

import com.pnomeer.lab.core.MetricPoint;

public record StoredMetric(long sequence, MetricPoint point) { }
