import { describe, expect, it } from 'vitest';
import evidence from '../../contracts/dashboard/v1/fixtures/evidence.json';
import type { Comparison, Run } from './api';
import { runComparisonDraft, seriesComparisonDraft } from './findingDraft';
import type { SeriesComparison } from './series';

const run = (id: string, parameters: Record<string, string>) =>
  ({ ...evidence.run, runId: id, runMetadata: { ...evidence.run.runMetadata, parameters } }) as Run;

describe('finding drafts', () => {
  it('fills recorded run facts and leaves conclusions as placeholders', () => {
    const comparison: Comparison = {
      baseline: run('base', { 'scenario.hypothesis': 'Waiting grows with a tiny queue', 'scenario.definition.sha256': 'aaa' }),
      candidate: run('cand', { 'scenario.definition.sha256': 'bbb', 'input.sha256': 'ccc' }),
      cautions: ['Different Git commits'],
      conditions: [
        { field: 'parameters.queueCapacity', baseline: '2', candidate: '8', different: true },
        { field: 'environment.javaVersion', baseline: '25', candidate: '25', different: false },
      ],
      values: [
        { kind: 'counter', name: 'items.consumed', unit: 'items', baseline: 10, candidate: null },
      ],
    };
    const md = runComparisonDraft(comparison);
    expect(md).toContain('# Finding: <작성 필요>');
    expect(md).toContain('상태: 초안');
    expect(md).toContain('`parameters.queueCapacity`: "2" → "8"');
    expect(md).not.toContain('environment.javaVersion');
    expect(md).toContain('Waiting grows with a tiny queue');
    expect(md).toContain('input SHA-256: `ccc`');
    expect(md).toContain('| items.consumed (counter) / items | 10 | 미측정 | 각 1회 / 변동 미측정 | Run summary |');
    expect(md).toContain('서버 주의: Different Git commits');
    for (const heading of ['Problem / Observed Fact', 'Evidence', 'Measured Result', 'Interpretation', 'Decision'])
      expect(md).toContain('## ' + heading);
  });

  it('reports series distributions with repetition counts and the overlap verdict', () => {
    const stats = (median: number, cv: number | null) => ({
      count: 3, min: median - 2, max: median + 2, mean: median, median, p95: median + 2, standardDeviation: 1, coefficientOfVariation: cv,
    });
    const view = (id: string) => ({
      series: { seriesId: id, status: 'SUCCEEDED', scenarioId: 's', repetitions: 3, warmup: 1, definitionSha256: 'd-' + id, experimentType: 'queue' },
      members: [{ position: 0, runId: id + '-w', warmup: true }, { position: 1, runId: id + '-1', warmup: false }],
      measuredSucceeded: 3,
      measures: [],
    });
    const noisy = {
      ...view('B'),
      stability: { highVariationRoles: ['elapsed'] },
      overlappingRunIds: ['x'],
      checks: [{ checkId: 'overlap', runs: 3, passed: 2, failed: 1 }, { checkId: 'order', runs: 3, passed: 3, failed: 0 }],
    };
    const md = seriesComparisonDraft({
      baseline: view('A'),
      candidate: noisy,
      sameDefinition: false,
      measures: [{ role: 'elapsed', name: 'time.elapsedMs', unit: 'ms', baseline: stats(100, 0.02), candidate: stats(130, null),
        medianChangePct: 30, separation: 'SEPARATED' }],
    } as unknown as SeriesComparison);
    expect(md).toContain('측정 Run: `A-1`');
    expect(md).not.toContain('`A-w`');
    expect(md).toContain('| elapsed `time.elapsedMs` / ms | 100 (98–102) | 130 (128–132) | n=3/3 · CV 2%/미측정 · 중앙값 변화 30% | 묶음 통계 |');
    expect(md).toContain('elapsed: 구분됨');
    expect(md).toContain('- Candidate 묶음은 변동 계수 10% 초과: elapsed');
    expect(md).toContain('- Candidate 묶음 반복 중 다른 Run 1개가 함께 실행됐다.');
    expect(md).not.toContain('- Baseline 묶음은');
    expect(md).toContain('- Candidate 묶음에서 판정이 달라진 CHECK: overlap 2/3 통과');
  });
});
