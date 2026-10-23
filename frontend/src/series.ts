import { apiUrl } from './api';

export type Statistics = {
  count: number;
  min: number | null;
  max: number | null;
  mean: number | null;
  median: number | null;
  p95: number | null;
  standardDeviation: number | null;
  coefficientOfVariation: number | null;
};
export type Series = {
  sequence: number;
  seriesId: string;
  experimentType: string;
  scenarioId: string;
  definitionSha256: string;
  repetitions: number;
  warmup: number;
  status: string;
  createdAtEpochMs: number;
  completedAtEpochMs: number;
  failureMessage: string | null;
};
export type SeriesView = {
  series: Series;
  members: {
    position: number;
    runId: string;
    warmup: boolean;
    status: string | null;
    startedAtEpochMs: number;
    completedAtEpochMs: number;
  }[];
  measuredSucceeded: number;
  measures: { role: string; name: string; unit: string; kind: string; statistics: Statistics }[];
  /** Other runs that executed while a repetition ran; absent from servers before this field existed. */
  overlappingRunIds?: string[];
  stability?: {
    variationThreshold: number;
    highVariationRoles: string[];
    load1mMin: number | null;
    load1mMax: number | null;
    availableProcessors: number | null;
  } | null;
};
export type Separation = 'INSUFFICIENT' | 'OVERLAPPING' | 'SEPARATED';
export type SeriesComparison = {
  baseline: SeriesView;
  candidate: SeriesView;
  sameDefinition: boolean;
  measures: {
    role: string;
    name: string;
    unit: string;
    baseline: Statistics;
    candidate: Statistics;
    medianChangePct: number | null;
    separation: Separation;
  }[];
};

export const MAX_REPETITIONS = 50;
export const MAX_WARMUP = 10;
export const activeSeries = (status: string) => status === 'QUEUED' || status === 'RUNNING';

export const separationLabels: Record<Separation, string> = {
  INSUFFICIENT: '근거 부족 · 측정 반복 3회 미만',
  OVERLAPPING: '차이 불분명 · 관측 범위가 겹침',
  SEPARATED: '구분됨 · 모든 반복이 상대 범위 밖',
};

/** Progress counts finished members against the planned total; it never extrapolates time. */
export function seriesProgress(view: SeriesView) {
  const total = view.series.repetitions + view.series.warmup;
  const finished = view.members.filter(
    (m) => m.status === 'SUCCEEDED' || m.status === 'FAILED' || m.status === 'INTERRUPTED',
  ).length;
  const failed = view.members.filter(
    (m) => !m.warmup && (m.status === 'FAILED' || m.status === 'INTERRUPTED'),
  ).length;
  return { total, finished, failed };
}

/** One shared linear scale for two observed ranges, padded so endpoints never sit on the edge. */
export function rangeScale(a: Statistics, b: Statistics) {
  const values = [a.min, a.max, b.min, b.max].filter((v): v is number => v !== null && Number.isFinite(v));
  if (!values.length) return null;
  let lo = Math.min(...values),
    hi = Math.max(...values);
  if (lo === hi) {
    const pad = Math.abs(lo) * 0.05 || 1;
    lo -= pad;
    hi += pad;
  }
  const pad = (hi - lo) * 0.05;
  lo -= pad;
  hi += pad;
  return { lo, hi, at: (v: number) => ((v - lo) / (hi - lo)) * 100 };
}

export async function submitSeries(
  document: unknown,
  repetitions: number,
  warmup: number,
): Promise<string> {
  const query = new URLSearchParams({ repetitions: String(repetitions), warmup: String(warmup) });
  let response: Response;
  try {
    response = await fetch(apiUrl('/run-series?' + query), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(document),
      signal: AbortSignal.timeout(15000),
    });
  } catch {
    throw new Error(
      '접수 여부를 확인할 수 없습니다. 반복 측정 목록에서 확인한 뒤 다시 실행하세요. 자동 재시도하지 않습니다.',
    );
  }
  if (response.status >= 400 && response.status < 500)
    throw new Error(`반복 측정 요청이 거절되었습니다 (${response.status}). 입력과 반복 횟수를 확인하세요.`);
  if (response.status !== 202)
    throw new Error(`반복 측정을 접수하지 못했습니다 (${response.status}). 서버 상태를 확인하세요.`);
  const body = await response.json();
  if (typeof body.seriesId !== 'string' || !body.seriesId) throw new Error('응답에 seriesId가 없습니다.');
  return body.seriesId;
}

export type Verdict = 'OK' | 'IMPROVEMENT' | 'REGRESSION' | 'CHANGED' | 'INSUFFICIENT';
export type RegressionCheck = {
  overall: 'PASS' | 'REGRESSION' | 'INSUFFICIENT' | 'NO_BASELINE';
  cautions?: string[];
  baselineSeriesId: string | null;
  candidateSeriesId: string;
  tolerancePct: number;
  measures: {
    role: string;
    name: string;
    unit: string;
    direction: 'LOWER_IS_BETTER' | 'HIGHER_IS_BETTER' | 'UNKNOWN';
    baselineMedian: number | null;
    candidateMedian: number | null;
    changePct: number | null;
    separation: Separation;
    verdict: Verdict;
  }[];
};
export const overallLabels: Record<RegressionCheck['overall'], string> = {
  PASS: '통과 · 기준선 대비 분리된 악화 없음',
  REGRESSION: '회귀 의심 · 허용 오차를 넘고 범위가 분리된 악화',
  INSUFFICIENT: '판정 불가 · 측정 반복 3회 미만',
  NO_BASELINE: '기준선 없음',
};
export const verdictLabels: Record<Verdict, string> = {
  OK: '변화 없음',
  IMPROVEMENT: '개선',
  REGRESSION: '회귀',
  CHANGED: '변화(방향 미정)',
  INSUFFICIENT: '근거 부족',
};
export const directionLabels = {
  LOWER_IS_BETTER: '낮을수록 좋음',
  HIGHER_IS_BETTER: '높을수록 좋음',
  UNKNOWN: '방향 미정',
} as const;

export async function pinBaseline(seriesId: string): Promise<void> {
  const response = await fetch(apiUrl('/run-series/' + encodeURIComponent(seriesId) + '/baseline'), {
    method: 'PUT',
    signal: AbortSignal.timeout(15000),
  });
  if (response.status === 409)
    throw new Error('기준선은 성공한 묶음 중 측정 반복이 3회 이상인 경우만 지정할 수 있습니다.');
  if (!response.ok) throw new Error(`기준선을 지정하지 못했습니다 (${response.status}).`);
}

/** Stops before the next repetition (series) or point (sweep); the run in progress finishes. */
export async function cancelMeasurement(kind: 'run-series' | 'run-sweeps', id: string): Promise<void> {
  const response = await fetch(apiUrl(`/${kind}/${encodeURIComponent(id)}/cancel`), {
    method: 'POST',
    signal: AbortSignal.timeout(15000),
  });
  if (response.status === 409) throw new Error('이미 끝났습니다.');
  if (!response.ok) throw new Error(`중지하지 못했습니다 (${response.status}).`);
}

export type Sweep = {
  sequence: number;
  sweepId: string;
  experimentType: string;
  scenarioId: string;
  grid: Record<string, (string | number | boolean)[]>;
  repetitions: number;
  warmup: number;
  status: string;
  createdAtEpochMs: number;
  completedAtEpochMs: number;
  failureMessage: string | null;
};
export type SweepView = {
  sweep: Sweep;
  points: { position: number; parameters: Record<string, string | number | boolean>; series: SeriesView | null }[];
};
export const MAX_SWEEP_POINTS = 24;

/** "1, 4, 16" → typed values; integers stay numbers so the server validates ranges. */
export function parseSweepValues(text: string, type: string): (string | number | boolean)[] {
  const parts = text.split(',').map((v) => v.trim()).filter((v) => v !== '');
  if (type === 'integer') {
    if (parts.some((v) => !/^-?\d+$/.test(v))) throw new Error('정수만 쉼표로 구분해 입력하세요.');
    return parts.map(Number);
  }
  if (type === 'boolean') {
    if (parts.some((v) => v !== 'true' && v !== 'false')) throw new Error('true 또는 false만 입력하세요.');
    return parts.map((v) => v === 'true');
  }
  return parts;
}

export function sweepPointCount(grid: Record<string, unknown[]>) {
  return Object.values(grid).reduce((n, values) => n * values.length, 1);
}

/** Median of one measure per point, in point order; null where the point has no statistics yet. */
export function sweepMeasure(view: SweepView, role: string) {
  return view.points.map((p) => {
    const measure = p.series?.measures.find((m) => m.role === role);
    return { point: p, stats: measure?.statistics ?? null, unit: measure?.unit ?? '' };
  });
}

export async function submitSweep(
  definition: unknown,
  grid: Record<string, unknown[]>,
  repetitions: number,
  warmup: number,
): Promise<string> {
  const query = new URLSearchParams({ repetitions: String(repetitions), warmup: String(warmup) });
  let response: Response;
  try {
    response = await fetch(apiUrl('/run-sweeps?' + query), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ definition, grid }),
      signal: AbortSignal.timeout(15000),
    });
  } catch {
    throw new Error('접수 여부를 확인할 수 없습니다. 반복 측정 목록에서 확인한 뒤 다시 실행하세요.');
  }
  if (response.status >= 400 && response.status < 500) {
    let detail = '';
    try {
      detail = (await response.json()).detail ?? '';
    } catch {
      /* keep the status-only message */
    }
    throw new Error(`스윕 요청이 거절되었습니다 (${response.status}). ${detail}`.trim());
  }
  if (response.status !== 202) throw new Error(`스윕을 접수하지 못했습니다 (${response.status}).`);
  const body = await response.json();
  if (typeof body.sweepId !== 'string' || !body.sweepId) throw new Error('응답에 sweepId가 없습니다.');
  return body.sweepId;
}
