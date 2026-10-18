import { describe, expect, it } from 'vitest';
import evidence from '../../contracts/dashboard/v1/fixtures/evidence.json';
import type { MetricPage, Run } from './api';
import { CHART_POINT_LIMIT, chartPath, metricCharts } from './chartData';
const run = {
  ...evidence.run,
  startedAtEpochMs: 1000,
  runMetadata: { ...evidence.run.runMetadata, parameters: { queueCapacity: '4' } },
} as Run;
const sample = (
  sequence: number,
  timestamp: number,
  gauges: Record<string, number> = {},
  counters: Record<string, number> = {},
  labels: Record<string, string> = {},
): MetricPage['metrics'][number] => ({
  sequence,
  point: { timestampEpochMs: timestamp, gauges, counters, labels },
});

describe('metric graph semantics', () => {
  it('derives queue throughput from adjacent consumed counts and occupancy from depth, not peak', () => {
    const [rate, queue] = metricCharts(run, [
      sample(0, 1000, { 'queue.depth': 0, 'queue.maxDepth': 4 }, { 'items.consumed': 0 }),
      sample(1, 1500, { 'queue.depth': 2, 'queue.maxDepth': 4 }, { 'items.consumed': 10 }),
    ]);
    expect(rate.unit).toBe('items/s');
    expect(rate.rows.map((row) => row.values)).toEqual([[null], [20]]);
    expect(queue.rows.map((row) => row.values)).toEqual([[0], [50]]);
  });
  it('uses queue monotonic elapsed time when wall clock moves backwards', () => {
    const [rate] = metricCharts(run, [
      sample(0, 1000, {}, { 'items.consumed': 0, 'time.elapsedMs': 0 }),
      sample(1, 900, {}, { 'items.consumed': 10, 'time.elapsedMs': 250 }),
    ]);
    expect(rate.rows[1].seconds).toBe(0.25);
    expect(rate.rows[1].values).toEqual([40]);
  });
  it('does not derive rates across resets, missing counts, duplicate or reversed clocks', () => {
    const [rate] = metricCharts(run, [
      sample(0, 1000, { 'queue.depth': 0 }, { 'items.consumed': 5 }),
      sample(1, 1000, { 'queue.depth': 1 }, { 'items.consumed': 10 }),
      sample(2, 900, { 'queue.depth': 1 }, { 'items.consumed': 20 }),
      sample(3, 2000, { 'queue.depth': 1 }, { 'items.consumed': 1 }),
      sample(4, 2500, { 'queue.depth': 1 }),
      sample(5, 3000, { 'queue.depth': 1 }, { 'items.consumed': 4 }),
    ]);
    expect(rate.rows.every((row) => row.values[0] === null)).toBe(true);
    expect(rate.rows[1].seconds).toBeNull();
    expect(rate.rows[2].seconds).toBeNull();
  });
  it('keeps missing and impossible queue capacity out of percentage graphs', () => {
    for (const capacity of ['', '0', '-1', 'bad']) {
      const [, queue] = metricCharts(
        { ...run, runMetadata: { ...run.runMetadata, parameters: { queueCapacity: capacity } } },
        [sample(0, 1000, { 'queue.depth': 2 })],
      );
      expect(queue.rows[0].values).toEqual([null]);
    }
    const [, over] = metricCharts(run, [sample(0, 1000, { 'queue.depth': 5 })]);
    expect(over.rows[0].values).toEqual([null]);
  });
  it('uses recorded pipeline interval rates and monotonic elapsed time, retaining gaps and zero', () => {
    const pipeline = { ...run, experimentType: 'pipeline.excel-ingestion' };
    const [rate, queue] = metricCharts(pipeline, [
      sample(
        0,
        9000,
        { 'queue.raw.saturationPct': 0, 'queue.mapped.saturationPct': 25 },
        { 'time.elapsedMs': 250, 'throughput.insertedPerSec': 100 },
      ),
      sample(
        1,
        8000,
        { 'queue.raw.saturationPct': 101 },
        { 'time.elapsedMs': 500 },
        { source: 'pipeline.progressSnapshot' },
      ),
      sample(
        2,
        7000,
        { 'queue.raw.saturationPct': 50 },
        { 'time.elapsedMs': 750, 'throughput.insertedPerSec': 200 },
      ),
    ]);
    expect(rate.unit).toBe('rows/s');
    expect(rate.rows.map((row) => row.seconds)).toEqual([0.25, 0.5, 0.75]);
    expect(rate.rows.map((row) => row.values[2])).toEqual([100, null, 200]);
    expect(queue.rows.map((row) => row.values[0])).toEqual([0, null, 50]);
    expect(
      chartPath(
        rate.rows,
        2,
        (n) => n,
        (n) => n,
      ),
    ).toBe('M0.25,100.00  M0.75,200.00');
  });
  it('does not turn summary-only or unknown worker data into a time series', () => {
    expect(metricCharts(run, [])[0].rows).toEqual([]);
    expect(
      metricCharts({ ...run, experimentType: 'career.mcp-platform.architecture' }, [
        sample(0, 1000, { 'throughput.insertedPerSec': 100 }),
      ]),
    ).toEqual([]);
    expect(metricCharts(run, [sample(0, 1000, { 'jvm.heap.usedBytes': 100 })])[0].rows).toEqual([]);
  });
  it('caps graph points and handles NaN and Infinity as missing', () => {
    const input = Array.from({ length: CHART_POINT_LIMIT + 20 }, (_, i) =>
      sample(
        i,
        1000 + i * 100,
        { 'queue.depth': i === 20 ? NaN : Infinity },
        { 'items.consumed': i },
      ),
    );
    const [rate, queue] = metricCharts(run, input);
    expect(rate.rows).toHaveLength(CHART_POINT_LIMIT);
    expect(rate.rows[0].sequence).toBe(20);
    expect(rate.rows[0].values[0]).toBeNull();
    expect(queue.rows.every((row) => row.values[0] === null)).toBe(true);
  });
  it('labels IO cumulative average as requests/s, without implying queue observations', () => {
    const charts = metricCharts({ ...run, experimentType: 'io.endpoint-comparison' }, [
      sample(0, 1500, { 'throughput.totalRequestsPerSec': 40 }),
    ]);
    expect(charts).toHaveLength(1);
    expect(charts[0].unit).toBe('requests/s');
    expect(charts[0].description).toContain('누적 평균');
  });
  it('adds wait and process-heap charts only when a pipeline window recorded them', () => {
    const pipeline = { ...run, experimentType: 'pipeline.excel-ingestion' } as Run;
    const withResources = metricCharts(pipeline, [
      sample(0, 1000, { 'queue.raw.enqueueWaitMs': 0.5, 'resource.heapUsedMb': 40, 'resource.peakHeapMb': 42, 'resource.heapMaxMb': 128, 'time.elapsedMs': 0 }, { 'throughput.producedPerSec': 10 }),
      sample(1, 1500, { 'queue.raw.enqueueWaitMs': 2, 'resource.heapUsedMb': 55, 'resource.peakHeapMb': 55, 'resource.heapMaxMb': 128, 'time.elapsedMs': 500 }, { 'throughput.producedPerSec': 12 }),
    ]);
    expect(withResources.map((m) => m.title)).toEqual(['처리량 추이', '큐 점유율', '큐 평균 put 대기', '힙 사용량']);
    expect(withResources[2].rows.map((r) => r.values)).toEqual([[0.5, null], [2, null]]);
    expect(withResources[3].description).toContain('최대 128 MiB');
    const legacy = metricCharts(pipeline, [sample(0, 1000, { 'queue.raw.size': 1 }, { 'throughput.producedPerSec': 1 })]);
    expect(legacy.map((m) => m.title)).toEqual(['처리량 추이', '큐 점유율']);
  });
});
