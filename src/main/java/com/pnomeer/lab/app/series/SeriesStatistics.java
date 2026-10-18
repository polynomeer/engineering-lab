package com.pnomeer.lab.app.series;

import java.util.Arrays;
import java.util.List;

/**
 * Distribution of one measure across measured repetitions. Percentiles use nearest rank so every
 * reported value is an observed run, never an interpolation.
 */
public record SeriesStatistics(int count, Double min, Double max, Double mean, Double median, Double p95,
        Double standardDeviation, Double coefficientOfVariation) {
    public static final int MIN_FOR_COMPARISON = 3;

    public static SeriesStatistics of(List<Double> values) {
        double[] sorted = values.stream().filter(v -> v != null && Double.isFinite(v)).mapToDouble(Double::doubleValue).sorted().toArray();
        int n = sorted.length;
        if (n == 0) return new SeriesStatistics(0, null, null, null, null, null, null, null);
        double mean = Arrays.stream(sorted).sum() / n;
        double median = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
        Double deviation = null, variation = null;
        if (n > 1) {
            double squares = Arrays.stream(sorted).map(v -> (v - mean) * (v - mean)).sum();
            deviation = Math.sqrt(squares / (n - 1));
            variation = mean == 0 ? null : deviation / Math.abs(mean);
        }
        return new SeriesStatistics(n, sorted[0], sorted[n - 1], mean, median, nearestRank(sorted, 0.95), deviation, variation);
    }

    static double nearestRank(double[] sorted, double quantile) {
        int rank = (int) Math.ceil(quantile * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    public enum Separation { INSUFFICIENT, OVERLAPPING, SEPARATED }

    /**
     * Conservative: two series are only "separated" when every measured repetition of one lies
     * outside the observed range of the other. Overlap means the difference is not established.
     */
    public static Separation separation(SeriesStatistics baseline, SeriesStatistics candidate) {
        if (baseline.count < MIN_FOR_COMPARISON || candidate.count < MIN_FOR_COMPARISON) return Separation.INSUFFICIENT;
        boolean apart = candidate.min > baseline.max || candidate.max < baseline.min;
        return apart ? Separation.SEPARATED : Separation.OVERLAPPING;
    }

    /** Relative change of the candidate median over the baseline median, in percent. */
    public static Double medianChange(SeriesStatistics baseline, SeriesStatistics candidate) {
        if (baseline.median == null || candidate.median == null) return null;
        if (baseline.median == 0) return candidate.median == 0 ? 0.0 : null; // a change from zero has no relative size
        return (candidate.median - baseline.median) / Math.abs(baseline.median) * 100;
    }
}
