import { describe, expect, it } from 'vitest';
import { parseSweepValues, rangeScale, seriesProgress, sweepPointCount, type SeriesView, type Statistics } from './series';

const stats = (min: number | null, max: number | null): Statistics => ({
  count: 3, min, max, mean: null, median: null, p95: null, standardDeviation: null, coefficientOfVariation: null,
});

describe('series helpers', () => {
  it('places both ranges on one padded scale', () => {
    const scale = rangeScale(stats(100, 200), stats(300, 400))!;
    expect(scale.lo).toBe(85);
    expect(scale.hi).toBe(415);
    expect(scale.at(100)).toBeGreaterThan(0);
    expect(scale.at(400)).toBeLessThan(100);
    expect(rangeScale(stats(null, null), stats(null, null))).toBeNull();
    const flat = rangeScale(stats(5, 5), stats(5, 5))!;
    expect(flat.at(5)).toBeCloseTo(50);
  });

  it('counts finished members and failed measured runs only', () => {
    const view = {
      series: { repetitions: 3, warmup: 1 },
      members: [
        { position: 0, warmup: true, status: 'FAILED' },
        { position: 1, warmup: false, status: 'SUCCEEDED' },
        { position: 2, warmup: false, status: 'INTERRUPTED' },
        { position: 3, warmup: false, status: 'RUNNING' },
      ],
    } as unknown as SeriesView;
    expect(seriesProgress(view)).toEqual({ total: 4, finished: 3, failed: 1 });
  });

  it('parses typed sweep values and counts grid points', () => {
    expect(parseSweepValues(' 1, 4 ,16', 'integer')).toEqual([1, 4, 16]);
    expect(() => parseSweepValues('1, x', 'integer')).toThrow();
    expect(parseSweepValues('true,false', 'boolean')).toEqual([true, false]);
    expect(parseSweepValues('A, B', 'string')).toEqual(['A', 'B']);
    expect(sweepPointCount({ a: [1, 2], b: [1, 2, 3] })).toBe(6);
  });
});
