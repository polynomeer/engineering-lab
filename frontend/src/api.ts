export type Run = {
  sequence: number;
  runId: string;
  experimentType: string;
  scenarioId: string;
  status: string;
  createdAtEpochMs: number;
  startedAtEpochMs: number;
  completedAtEpochMs: number;
  failureMessage: string | null;
  runMetadata: {
    parameters: Record<string, string>;
    environment: Record<string, unknown>;
    git: Record<string, unknown>;
  };
  summary: {
    counters: Record<string, number>;
    gauges: Record<string, number>;
    details: Record<string, string>;
  };
};
export type Descriptor = {
  id: string;
  title: string;
  category: string;
  input: string;
  measurementModes: string[];
  parameters: Record<
    string,
    {
      type: string;
      minimum: number | null;
      maximum: number | null;
      values: string[];
      unit: string | null;
      defaultValue: number | null;
    }
  >;
};
export type Module = {
  module: { id: string; title: string; collection: string };
  experiments: Descriptor[];
};
export type MetricPage = {
  metrics: {
    sequence: number;
    point: {
      timestampEpochMs: number;
      gauges: Record<string, number>;
      counters: Record<string, number>;
      labels: Record<string, string>;
    };
  }[];
  nextAfter: number | null;
};
export type Artifact = {
  artifactId: string;
  relativePath: string;
  mediaType: string;
  byteSize: number;
  sha256: string;
};
export type Comparison = {
  baseline: Run;
  candidate: Run;
  cautions: string[];
  conditions: { field: string; baseline: unknown; candidate: unknown; different: boolean }[];
  values: {
    kind: string;
    name: string;
    unit: string;
    baseline: number | null;
    candidate: number | null;
  }[];
};
export class ApiError extends Error {
  constructor(public status: number) {
    super(`요청 실패 (${status}). 서버 연결과 Run ID를 확인하세요.`);
  }
}
export const apiUrl = (path: string) => {
  if (!path.startsWith('/') || path.startsWith('//')) throw new Error('Invalid API path');
  return '/api' + path;
};
export async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(apiUrl(path), {
    signal: signal
      ? AbortSignal.any([signal, AbortSignal.timeout(15000)])
      : AbortSignal.timeout(15000),
  });
  if (!response.ok) throw new ApiError(response.status);
  if (!response.headers.get('content-type')?.includes('application/json'))
    throw new Error('API가 JSON 대신 다른 응답을 반환했습니다. proxy 설정을 확인하세요.');
  return response.json() as Promise<T>;
}
export async function optional<T>(path: string, signal?: AbortSignal): Promise<T | null> {
  try {
    return await get<T>(path, signal);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}
export const terminal = (run: Run) => ['SUCCEEDED', 'FAILED', 'INTERRUPTED'].includes(run.status);
export function artifactReason(run: Run, artifact: Artifact, maxBytes: number): string | null {
  const uuid = '[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}';
  const jfr =
    new RegExp('^jfr_' + uuid + '$').test(artifact.artifactId) &&
    artifact.relativePath === `jfr/${artifact.artifactId}.jfr` &&
    artifact.mediaType === 'application/octet-stream';
  const log =
    new RegExp('^log_' + uuid + '$').test(artifact.artifactId) &&
    artifact.relativePath === `logs/${artifact.artifactId}.ndjson` &&
    artifact.mediaType === 'application/x-ndjson';
  if (!jfr && !log) return '참조 전용';
  if (!terminal(run)) return '실행 종료 후 다운로드';
  if (artifact.byteSize > maxBytes) return '다운로드 크기 제한 초과';
  return null;
}
export function parseRoute(path: string, search: string) {
  const query = new URLSearchParams(search);
  if (path === '/app' || path === '/app/' || path === '/app/runs')
    return { kind: 'list' as const, query };
  if (path === '/app/settings') return { kind: 'settings' as const, query };
  if (path === '/app/tools/ingestion-db') return { kind: 'database' as const, query };
  if (path === '/app/tools/io-echo') return { kind: 'echo' as const, query };
  if (path === '/app/experiments') return { kind: 'execute' as const, query };
  if (path === '/app/compare') return { kind: 'compare' as const, query };
  const match = /^\/app\/runs\/([^/]+)$/.exec(path);
  if (match) {
    try {
      return { kind: 'detail' as const, id: decodeURIComponent(match[1]), query };
    } catch {
      return { kind: 'missing' as const, query };
    }
  }
  return { kind: 'missing' as const, query };
}
