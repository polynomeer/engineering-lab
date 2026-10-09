import { apiUrl, type Descriptor, type Comparison } from './api';
export type Draft = {
  id: string;
  hypothesis: string;
  parameters: Record<string, string | number | boolean>;
};
export const presets: Record<string, Draft[]> = {
  'career.mcp-platform.sequence': ['ATOMIC_UPDATE', 'BEFORE', 'SELECT_FOR_UPDATE'].map(
    (variant) => ({
      id: 'sequence-' + variant.toLowerCase().replaceAll('_', '-'),
      hypothesis:
        variant === 'BEFORE'
          ? '동시 채번의 겹침을 재현한다'
          : '원자적인 채번으로 겹침 없이 전체 구간을 할당한다',
      parameters: { variant, workers: 10, blockSize: 1000 },
    }),
  ),
  'concurrency.queue-contention': [
    ['queue-quick-demo', 4, 2, 1, 40, 1],
    ['queue-backpressure-stress', 2, 3, 1, 150, 6],
    ['queue-burst-producers', 8, 4, 2, 200, 2],
  ].map(
    ([id, queueCapacity, producerThreads, consumerThreads, itemsPerProducer, consumerDelayMs]) => ({
      id: String(id),
      hypothesis: '큐 크기와 생산·소비 속도에 따른 대기 시간 비교',
      parameters: {
        queueCapacity,
        producerThreads,
        consumerThreads,
        itemsPerProducer,
        consumerDelayMs,
      },
    }),
  ),
  'io.endpoint-comparison': [
    ['io-fast-echo', 10, 5, false, 5],
    ['io-pinned-virtual-thread', 16, 20, true, 60],
    ['io-long-wait', 12, 120, false, 20],
  ].map(([id, requests, delayMs, pinning, pinDelayMs]) => ({
    id: String(id),
    hypothesis: '동일 요청 부하에서 IO 방식별 처리량과 지연 비교',
    parameters: { requests, delayMs, pinning, pinDelayMs },
  })),
};
export function initialDraft(descriptor: Descriptor): Draft {
  return (
    presets[descriptor.id]?.[0] ?? {
      id: 'new-scenario',
      hypothesis: '',
      parameters: Object.fromEntries(
        Object.entries(descriptor.parameters).map(([key, spec]) => [
          key,
          spec.defaultValue ??
            (spec.type === 'boolean'
              ? false
              : spec.type === 'integer'
                ? spec.minimum
                : spec.values[0]),
        ]),
      ),
    }
  );
}
export function definition(descriptor: Descriptor, draft: Draft) {
  if (typeof draft.id !== 'string' || !/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,199}$/.test(draft.id))
    throw new Error(
      '시나리오 ID는 영문·숫자로 시작하는 200자 이내 영문·숫자·점·밑줄·하이픈이어야 합니다.',
    );
  if (
    typeof draft.hypothesis !== 'string' ||
    !draft.hypothesis.trim() ||
    draft.hypothesis.length > 4000
  )
    throw new Error('가설을 1–4000자로 입력하세요.');
  const parameters = Object.fromEntries(
    Object.entries(descriptor.parameters).map(([key, spec]) => {
      const raw = draft.parameters[key];
      const value =
        spec.type === 'integer' && typeof raw === 'string' && raw.trim() !== '' ? Number(raw) : raw;
      const valid =
        spec.type === 'integer'
          ? typeof value === 'number' &&
            Number.isSafeInteger(value) &&
            value >= spec.minimum! &&
            value <= spec.maximum!
          : spec.type === 'boolean'
            ? typeof value === 'boolean'
            : spec.type === 'string' && typeof value === 'string' && spec.values.includes(value);
      if (!valid) throw new Error(`${key}: 허용된 범위와 형식을 확인하세요.`);
      return [key, value];
    }),
  );
  return {
    schemaVersion: 1,
    experiment: descriptor.id,
    scenario: { id: draft.id, hypothesis: draft.hypothesis },
    parameters,
    measurement: { mode: 'SINGLE_PASS' },
  };
}
export function supportsForm(d: Descriptor) {
  return (
    d.input === 'NONE' &&
    d.measurementModes.includes('SINGLE_PASS') &&
    Object.values(d.parameters).every(
      (p) =>
        p.type === 'boolean' ||
        p.type === 'integer' ||
        (p.type === 'string' && p.values.length > 0),
    )
  );
}
export async function submitRun(document: ReturnType<typeof definition>): Promise<string> {
  try {
    const response = await fetch(apiUrl('/runs'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(document),
      signal: AbortSignal.timeout(15000),
    });
    if (response.status >= 400 && response.status < 500 && response.status !== 408)
      throw new Rejected(
        `실행 요청이 거절되었습니다 (${response.status}). 입력과 서버 상태를 확인하세요.`,
      );
    if (response.status !== 202) throw new Error('Unexpected status');
    const body = await response.json();
    if (typeof body.runId !== 'string' || !body.runId) throw new Error('Missing run ID');
    return body.runId;
  } catch (e) {
    if (e instanceof Rejected) throw e;
    throw new Error(
      '접수 여부를 확인할 수 없습니다. 중복 실행을 피하려면 실행 기록에서 시나리오 ID를 확인한 뒤 다시 실행하세요. 자동 재시도하지 않습니다.',
    );
  }
}
class Rejected extends Error {}
export const storageKey = 'engineering-lab.dashboard.presets.v1';
export type Saved = { experiment: string; draft: Draft };
export function readSaved(storage: Pick<Storage, 'getItem'>, catalog: Descriptor[]): Saved[] {
  const raw = storage.getItem(storageKey);
  if (!raw) return [];
  if (raw.length > 1024 * 1024 || new TextEncoder().encode(raw).byteLength > 1024 * 1024)
    throw new Error('저장된 설정이 1 MiB 제한을 초과했습니다.');
  const parsed = JSON.parse(raw);
  if (parsed?.version !== 1 || !Array.isArray(parsed.entries) || parsed.entries.length > 100)
    throw new Error('저장된 설정 형식을 확인하세요.');
  return parsed.entries
    .flatMap((entry: Saved) => {
      const descriptor = catalog.find((d) => d.id === entry?.experiment);
      if (!descriptor) return [];
      try {
        const document = definition(descriptor, entry.draft);
        return [
          {
            experiment: descriptor.id,
            draft: {
              id: document.scenario.id,
              hypothesis: document.scenario.hypothesis,
              parameters: document.parameters,
            },
          },
        ];
      } catch {
        return [];
      }
    })
    .slice(0, 12);
}
export function csv(rows: unknown[][]): string {
  return (
    '\ufeff' +
    rows
      .map((row) =>
        row
          .map((cell) => {
            let value =
              cell == null ? '' : typeof cell === 'object' ? JSON.stringify(cell) : String(cell);
            if (typeof cell === 'string' && /^[\s]*[=+@-]/.test(value)) value = "'" + value;
            return '"' + value.replaceAll('"', '""') + '"';
          })
          .join(','),
      )
      .join('\r\n')
  );
}
export function comparisonCsv(c: Comparison) {
  return csv([
    ['section', 'name', 'unit', 'baseline', 'candidate'],
    ['run', 'runId', '', c.baseline.runId, c.candidate.runId],
    ['run', 'status', '', c.baseline.status, c.candidate.status],
    ...c.cautions.map((text) => ['caution', text, '', '', '']),
    ...c.conditions.map((v) => ['condition', v.field, '', v.baseline, v.candidate]),
    ...c.values.map((v) => [v.kind, v.name, v.unit, v.baseline, v.candidate]),
  ]);
}
export function download(filename: string, content: string, type: string) {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
