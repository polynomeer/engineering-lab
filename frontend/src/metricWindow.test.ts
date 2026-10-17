import { describe, expect, it } from 'vitest';
import type { MetricPage } from './api';
import { advancedMetricCursor, appendMetricWindow } from './metricWindow';
const row = (sequence: number): MetricPage['metrics'][number] => ({ sequence, point: { timestampEpochMs: sequence, gauges: {}, counters: {}, labels: {} } });
describe('live metric window', () => {
  it('deduplicates overlapping pages and bounds the most recent observations', () => {
    const result = appendMetricWindow(Array.from({ length: 900 }, (_, i) => row(i)), Array.from({ length: 300 }, (_, i) => row(i + 800)));
    expect(result).toHaveLength(1000);
    expect(result[0].sequence).toBe(100);
    expect(result.at(-1)?.sequence).toBe(1099);
  });
  it('advances from rows even when the partial page has no next cursor', () => {
    expect(advancedMetricCursor(-1, { metrics: [row(0), row(1)], nextAfter: null })).toBe(1);
    expect(advancedMetricCursor(1, { metrics: [], nextAfter: null })).toBe(1);
  });
  it('rejects stalled pagination instead of endlessly requesting it', () => {
    expect(() => advancedMetricCursor(10, { metrics: [], nextAfter: 10 })).toThrow();
  });
});
