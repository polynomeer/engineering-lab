import type { Comparison, MetricPage, Run } from './api';

type Observation = MetricPage['metrics'][number];
const finite = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);
const nonnegative = (v: unknown): number | null => (finite(v) && v >= 0 ? v : null);
const summary = (run: Run, name: string) =>
  nonnegative(run.summary.counters[name] ?? run.summary.gauges[name]);
const parameter = (run: Run, ...names: string[]) => {
  for (const name of names) {
    const raw = run.runMetadata.parameters[name];
    if (typeof raw === 'string' && raw.trim() !== '') {
      const parsed = nonnegative(Number(raw));
      if (parsed !== null) return parsed;
    }
  }
  return null;
};
const observed = (point: Observation['point'], name: string) =>
  nonnegative(point.gauges[name] ?? point.counters[name]);
const count = (value: number | null) =>
  value === null ? '미기록' : value.toLocaleString('ko-KR', { maximumFractionDigits: 0 });

export function durationSeconds(run: Run): number | null {
  const { startedAtEpochMs: start, completedAtEpochMs: end } = run;
  return finite(start) && finite(end) && start > 0 && end >= start ? (end - start) / 1000 : null;
}

export type Kpi = {
  id: string;
  label: string;
  value: number;
  unit?: string;
  total?: number;
  detail: string;
  tone?: 'success' | 'danger' | 'accent';
};

/** Summary tiles use only recorded run counters; missing values are omitted, never zero-filled. */
export function runKpis(run: Run): Kpi[] {
  if (run.experimentType !== 'pipeline.excel-ingestion') return [];
  const inserted = summary(run, 'rows.inserted');
  const produced = summary(run, 'rows.produced');
  const unconfirmed = summary(run, 'rows.unconfirmed');
  const invalid = summary(run, 'rows.validationErrors');
  const retries = summary(run, 'retry.count');
  const attempts = parameter(run, 'pipeline.insert.max-attempts');
  const seconds = durationSeconds(run);
  const items: Kpi[] = [];
  if (inserted !== null)
    items.push({
      id: 'inserted',
      label: '저장 확인 행',
      value: inserted,
      total: produced ?? undefined,
      detail: `파싱 ${count(produced)}` + (unconfirmed === null ? '' : ` · 미확인 ${count(unconfirmed)}`),
      tone: produced !== null && inserted >= produced && !unconfirmed ? 'success' : undefined,
    });
  if (inserted !== null && seconds !== null && seconds > 0)
    items.push({
      id: 'throughput',
      label: '평균 저장 처리량',
      value: inserted / seconds,
      unit: 'rows/s',
      detail: '저장 확인 행 ÷ Run 경과 시간',
    });
  if (invalid !== null)
    items.push({
      id: 'invalid',
      label: '검증 실패 행',
      value: invalid,
      detail: invalid > 0 ? 'dead-letter에 행 문맥과 함께 기록' : 'dead-letter 없음',
      tone: invalid > 0 ? 'danger' : undefined,
    });
  if (retries !== null)
    items.push({
      id: 'retries',
      label: 'Insert 재시도',
      value: retries,
      unit: '회',
      detail: attempts !== null ? `batch당 최대 ${count(attempts)}회 시도` : '시도 상한 미기록',
      tone: retries > 0 ? 'accent' : undefined,
    });
  return items;
}

export type FlowStage = {
  kind: 'stage';
  id: string;
  title: string;
  workers: string | null;
  rate: number | null;
};
export type FlowQueue = {
  kind: 'queue';
  id: string;
  title: string;
  pct: number | null;
  size: number | null;
  capacity: number | null;
  waitMs: number | null;
};
export type PipelineFlow = {
  sequence: number;
  seconds: number | null;
  nodes: (FlowStage | FlowQueue)[];
  stored: number | null;
};
const flowKeys = [
  'throughput.producedPerSec',
  'throughput.mappedPerSec',
  'throughput.insertedPerSec',
  'queue.raw.saturationPct',
  'queue.mapped.saturationPct',
  'queue.raw.size',
  'queue.mapped.size',
];

/** Stage snapshot from the last pipeline observation on the loaded page. */
export function pipelineFlow(run: Run, observations: Observation[]): PipelineFlow | null {
  if (run.experimentType !== 'pipeline.excel-ingestion') return null;
  const last = [...observations]
    .reverse()
    .find(({ point }) => flowKeys.some((key) => key in point.gauges || key in point.counters));
  if (!last) return null;
  const { point } = last;
  const queue = (id: 'raw' | 'mapped', title: string): FlowQueue => {
    const size = observed(point, `queue.${id}.size`);
    const capacity = parameter(run, `pipeline.queue.${id}-capacity`, `${id}Capacity`);
    const direct = observed(point, `queue.${id}.saturationPct`);
    const pct =
      direct !== null && direct <= 100
        ? direct
        : size !== null && capacity && size <= capacity
          ? (size / capacity) * 100
          : null;
    return {
      kind: 'queue',
      id,
      title,
      pct,
      size,
      capacity,
      waitMs: observed(point, `queue.${id}.enqueueWaitMs`),
    };
  };
  const validators = parameter(run, 'pipeline.threads.validator');
  const inserters = parameter(run, 'pipeline.threads.inserter');
  const chunk = parameter(run, 'pipeline.insert.chunk-size');
  const elapsed = observed(point, 'time.elapsedMs');
  return {
    sequence: last.sequence,
    seconds: elapsed === null ? null : elapsed / 1000,
    stored: observed(point, 'rows.inserted'),
    nodes: [
      {
        kind: 'stage',
        id: 'parse',
        title: '파싱',
        workers: 'producer',
        rate: observed(point, 'throughput.producedPerSec'),
      },
      queue('raw', 'Raw queue'),
      {
        kind: 'stage',
        id: 'transform',
        title: '검증 · 매핑',
        workers: validators === null ? null : `validator × ${count(validators)}`,
        rate: observed(point, 'throughput.mappedPerSec'),
      },
      queue('mapped', 'Mapped queue'),
      {
        kind: 'stage',
        id: 'insert',
        title: 'Chunk insert',
        workers:
          inserters === null
            ? null
            : `inserter × ${count(inserters)}` + (chunk === null ? '' : ` · ${count(chunk)}행`),
        rate: observed(point, 'throughput.insertedPerSec'),
      },
    ],
  };
}

type CheckLike = { status: string; description: string };
const checkRank = (status: string) => (status === 'FAIL' ? 0 : status === 'PASS' ? 2 : 1);

/** Failures first (stable), with a description hoisted only when every row repeats it. */
export function checkOverview<T extends CheckLike>(checks: T[]) {
  const ordered = checks
    .map((check, index) => ({ check, index }))
    .sort((a, b) => checkRank(a.check.status) - checkRank(b.check.status) || a.index - b.index)
    .map(({ check }) => check);
  const first = checks[0]?.description ?? '';
  const shared =
    checks.length > 1 && first !== '' && checks.every((c) => c.description === first)
      ? first
      : null;
  const passed = checks.filter((c) => c.status === 'PASS').length;
  const failed = checks.filter((c) => c.status === 'FAIL').length;
  return { ordered, shared, passed, failed, other: checks.length - passed - failed };
}

export type Delta = {
  name: string;
  unit: string;
  baseline: number | null;
  candidate: number | null;
  change: number | null;
};

/** Relative change of candidate over baseline; zero or missing baselines are not divided. */
export function comparisonDeltas(values: Comparison['values']): Delta[] {
  return values.map((v) => ({
    name: v.name,
    unit: v.unit,
    baseline: v.baseline,
    candidate: v.candidate,
    change:
      finite(v.baseline) && finite(v.candidate) && v.baseline !== 0
        ? ((v.candidate - v.baseline) / Math.abs(v.baseline)) * 100
        : null,
  }));
}

export function conditionSummary(conditions: Comparison['conditions']) {
  const different = conditions.filter((c) => c.different).map((c) => c.field);
  return { total: conditions.length, same: conditions.length - different.length, different };
}
