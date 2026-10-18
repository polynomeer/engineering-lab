import { describe, expect, it } from 'vitest';
import evidence from '../../contracts/dashboard/v1/fixtures/evidence.json';
import type { MetricPage, Run } from './api';
import {
  checkOverview,
  comparisonDeltas,
  conditionSummary,
  deadLetterReasons,
  durationSeconds,
  pipelineFlow,
  runKpis,
} from './runInsights';

const pipeline = (
  counters: Record<string, number>,
  parameters: Record<string, string> = {},
): Run =>
  ({
    ...evidence.run,
    experimentType: 'pipeline.excel-ingestion',
    startedAtEpochMs: 1000,
    completedAtEpochMs: 3000,
    runMetadata: { ...evidence.run.runMetadata, parameters },
    summary: { counters, gauges: {}, details: {} },
  }) as Run;
const sample = (
  sequence: number,
  gauges: Record<string, number>,
  counters: Record<string, number> = {},
): MetricPage['metrics'][number] => ({
  sequence,
  point: { timestampEpochMs: 0, gauges, counters, labels: {} },
});

describe('run summary tiles', () => {
  it('uses recorded counters and omits missing ones instead of showing zero', () => {
    const kpis = runKpis(
      pipeline(
        { 'rows.inserted': 100, 'rows.produced': 100, 'retry.count': 2 },
        { 'pipeline.insert.max-attempts': '3' },
      ),
    );
    expect(kpis.map((k) => k.id)).toEqual(['inserted', 'throughput', 'retries']);
    expect(kpis[0]).toMatchObject({ value: 100, total: 100, tone: 'success' });
    expect(kpis[0].detail).toBe('파싱 100');
    expect(kpis[1].value).toBe(50);
    expect(kpis[2].detail).toBe('batch당 최대 3회 시도');
  });

  it('marks unconfirmed rows and validation failures without inferring success', () => {
    const kpis = runKpis(
      pipeline({
        'rows.inserted': 90,
        'rows.produced': 100,
        'rows.unconfirmed': 10,
        'rows.validationErrors': 4,
      }),
    );
    expect(kpis.find((k) => k.id === 'inserted')?.tone).toBeUndefined();
    expect(kpis.find((k) => k.id === 'invalid')).toMatchObject({ value: 4, tone: 'danger' });
  });

  it('shows nothing for other experiments or runs without a valid duration', () => {
    expect(runKpis(evidence.run as Run)).toEqual([]);
    expect(durationSeconds({ ...pipeline({}), completedAtEpochMs: 0 })).toBeNull();
  });
});

describe('pipeline flow snapshot', () => {
  it('reads the last pipeline observation and derives occupancy from size and capacity', () => {
    const run = pipeline(
      {},
      {
        'pipeline.queue.raw-capacity': '200',
        'pipeline.threads.validator': '4',
        'pipeline.threads.inserter': '2',
        'pipeline.insert.chunk-size': '500',
      },
    );
    const flow = pipelineFlow(run, [
      sample(0, { 'throughput.producedPerSec': 1 }),
      sample(1, {
        'throughput.producedPerSec': 1450,
        'queue.raw.size': 50,
        'queue.mapped.saturationPct': 25,
        'queue.raw.enqueueWaitMs': 34,
        'time.elapsedMs': 3000,
      }),
      sample(2, { 'jvm.heapUsedMb': 10 }),
    ])!;
    expect(flow.sequence).toBe(1);
    expect(flow.seconds).toBe(3);
    const [parse, raw, transform, mapped, insert] = flow.nodes;
    expect(parse).toMatchObject({ rate: 1450 });
    expect(raw).toMatchObject({ pct: 25, size: 50, capacity: 200, waitMs: 34 });
    expect(transform).toMatchObject({ workers: 'validator × 4', rate: null });
    expect(mapped).toMatchObject({ pct: 25, capacity: null });
    expect(insert).toMatchObject({ workers: 'inserter × 2 · 500행' });
  });

  it('returns null without pipeline observations', () => {
    expect(pipelineFlow(pipeline({}), [sample(0, { 'jvm.heapUsedMb': 1 })])).toBeNull();
    expect(pipelineFlow(evidence.run as Run, [])).toBeNull();
  });
});

describe('check overview', () => {
  it('orders failures first, keeps order otherwise, and hoists a repeated description', () => {
    const checks = [
      { checkId: 'a', status: 'PASS', description: 'fixture' },
      { checkId: 'b', status: 'FAIL', description: 'fixture' },
      { checkId: 'c', status: 'SKIPPED', description: 'fixture' },
      { checkId: 'd', status: 'PASS', description: 'fixture' },
    ];
    const view = checkOverview(checks);
    expect(view.ordered.map((c) => c.checkId)).toEqual(['b', 'c', 'a', 'd']);
    expect(view).toMatchObject({ shared: 'fixture', passed: 2, failed: 1, other: 1 });
    expect(checkOverview([{ status: 'PASS', description: 'x' }]).shared).toBeNull();
    expect(
      checkOverview([
        { status: 'PASS', description: 'x' },
        { status: 'PASS', description: 'y' },
      ]).shared,
    ).toBeNull();
  });
});

describe('comparison helpers', () => {
  it('computes relative change only from finite, non-zero baselines', () => {
    const deltas = comparisonDeltas([
      { kind: 'gauge', name: 'rate', unit: 'rows/s', baseline: 100, candidate: 350 },
      { kind: 'counter', name: 'retry', unit: 'count', baseline: 0, candidate: 2 },
      { kind: 'counter', name: 'missing', unit: 'count', baseline: null, candidate: 1 },
    ]);
    expect(deltas.map((d) => d.change)).toEqual([250, null, null]);
  });

  it('counts differing recorded conditions', () => {
    expect(
      conditionSummary([
        { field: 'input.sha256', baseline: 'a', candidate: 'a', different: false },
        { field: 'git.commit', baseline: 'a', candidate: 'b', different: true },
      ]),
    ).toEqual({ total: 2, same: 1, different: ['git.commit'] });
  });
});

describe('dead-letter reasons', () => {
  it('counts reasons on the loaded page, largest first then by name', () => {
    expect(
      deadLetterReasons([{ reason: 'col3 not int' }, { reason: 'col1 blank' }, { reason: 'col3 not int' }]),
    ).toEqual([
      { reason: 'col3 not int', count: 2 },
      { reason: 'col1 blank', count: 1 },
    ]);
    expect(deadLetterReasons([])).toEqual([]);
  });
});
