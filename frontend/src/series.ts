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
