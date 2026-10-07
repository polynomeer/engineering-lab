import { definition, storageKey, type Saved } from './execution';
import type { Descriptor } from './api';
export type Echo = { time: string; mode: string; elapsedMs: number; response: string };
export type LegacyEcho = {
  msg: string;
  delayMs: number;
  pinning: boolean;
  pinDelayMs: number;
  virtualAvgMs: number;
  reactiveAvgMs: number;
  winner: string;
};
export type Settings = {
  version: 1;
  entries: Saved[];
  echoes: Echo[];
  legacyIo: LegacyEcho[];
  baseline: string;
  candidate: string;
  filter: string;
};
export const emptySettings = (): Settings => ({
  version: 1,
  entries: [],
  echoes: [],
  legacyIo: [],
  baseline: '',
  candidate: '',
  filter: '',
});
export function boundedJson(raw: string): any {
  if (raw.length > 1048576 || new TextEncoder().encode(raw).length > 1048576)
    throw new Error('설정은 1 MiB 이하만 지원합니다.');
  return JSON.parse(raw);
}
function text(value: unknown, max: number): string {
  if (typeof value !== 'string' || value.length > max)
    throw new Error('설정 문자열 형식 또는 길이가 올바르지 않습니다.');
  return value;
}
function number(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0)
    throw new Error('이력의 숫자가 올바르지 않습니다.');
  return value;
}
function list(value: unknown): any[] {
  if (!Array.isArray(value) || value.length > 100) throw new Error('설정 목록은 최대 100개입니다.');
  return value;
}
export function validateSettings(value: any, catalog?: Descriptor[]): Settings {
  if (!value || value.version !== 1) throw new Error('지원하지 않는 설정 버전입니다.');
  const entries = list(value.entries).map((s: any) => {
    const experiment = text(s?.experiment, 200);
    const id = text(s?.draft?.id, 200),
      hypothesis = text(s?.draft?.hypothesis, 4000);
    if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(id) || !hypothesis.trim())
      throw new Error('시나리오 ID와 가설을 확인하세요.');
    const parameters = s.draft.parameters;
    if (
      !parameters ||
      typeof parameters !== 'object' ||
      Array.isArray(parameters) ||
      Object.keys(parameters).length > 32 ||
      Object.values(parameters).some(
        (v) =>
          !(
            typeof v === 'boolean' ||
            (typeof v === 'number' && Number.isSafeInteger(v)) ||
            (typeof v === 'string' && v.length <= 1000)
          ),
      )
    )
      throw new Error('파라미터 형식이 올바르지 않습니다.');
    const draft = { id, hypothesis, parameters };
    if (catalog) {
      const descriptor = catalog.find((d) => d.id === experiment);
      if (!descriptor) throw new Error('등록되지 않은 실험: ' + experiment);
      draft.parameters = definition(descriptor, draft).parameters;
    }
    return { experiment, draft };
  });
  const echoes = list(value.echoes ?? []).map((e: any) => {
    if (!['virtual-thread', 'reactive'].includes(e.mode))
      throw new Error('IO 방식이 올바르지 않습니다.');
    return {
      time: text(e.time, 100),
      mode: e.mode,
      elapsedMs: number(e.elapsedMs),
      response: text(e.response, 10000),
    };
  });
  const legacyIo = list(value.legacyIo ?? []).map((e: any) => {
    if (typeof e.pinning !== 'boolean') throw new Error('pinning은 boolean이어야 합니다.');
    return {
      msg: text(e.msg, 1000),
      delayMs: number(e.delayMs),
      pinning: e.pinning,
      pinDelayMs: number(e.pinDelayMs),
      virtualAvgMs: number(e.virtualAvgMs),
      reactiveAvgMs: number(e.reactiveAvgMs),
      winner: text(e.winner, 100),
    };
  });
  if (entries.length + echoes.length + legacyIo.length > 100)
    throw new Error('총 항목 수는 최대 100개입니다.');
  const runId = (v: unknown) => {
    const id = text(v ?? '', 200);
    if (id && !/^[a-zA-Z0-9._-]+$/.test(id)) throw new Error('비교 ID가 올바르지 않습니다.');
    return id;
  };
  return {
    version: 1,
    entries,
    echoes,
    legacyIo,
    baseline: runId(value.baseline),
    candidate: runId(value.candidate),
    filter: text(value.filter ?? '', 200),
  };
}
export function loadSettings(storage: Pick<Storage, 'getItem'> = localStorage): Settings {
  const raw = storage.getItem(storageKey);
  return raw ? validateSettings(boundedJson(raw)) : emptySettings();
}
export function saveSettings(value: Settings, storage: Pick<Storage, 'setItem'> = localStorage) {
  const sanitized = validateSettings(value);
  const raw = JSON.stringify(sanitized);
  boundedJson(raw);
  storage.setItem(storageKey, raw); // One atomic write: presets, history and comparison move together.
}
export const legacyKeys = [
  'engineering-lab.experiments.dashboard',
  'engineering-lab.experiments.preset-history',
  'engineering-lab.io-history',
];
export function readLegacy(local: Pick<Storage, 'getItem'>, session: Pick<Storage, 'getItem'>) {
  return {
    format: 'engineering-lab-legacy',
    version: 1,
    local: Object.fromEntries(legacyKeys.map((k) => [k, local.getItem(k)])),
    session: {
      baseline: session.getItem('lab.compare.baseline'),
      candidate: session.getItem('lab.compare.candidate'),
    },
  };
}
export function parseTransfer(raw: string, catalog: Descriptor[]): Settings {
  const value = boundedJson(raw);
  if (value?.format !== 'engineering-lab-legacy') return validateSettings(value, catalog);
  if (
    value.version !== 1 ||
    !value.local ||
    typeof value.local !== 'object' ||
    Array.isArray(value.local) ||
    !value.session ||
    typeof value.session !== 'object' ||
    Array.isArray(value.session)
  )
    throw new Error('이전 설정 형식이 올바르지 않습니다.');
  const read = (key: string, fallback: any) =>
    value.local[key] == null ? fallback : boundedJson(text(value.local[key], 1048576));
  const dashboard = read(legacyKeys[0], {}),
    history = list(read(legacyKeys[1], []));
  const result = emptySettings();
  const add = (mode: string, payload: any) => {
    const experiment =
      mode === 'queue'
        ? 'concurrency.queue-contention'
        : mode === 'io'
          ? 'io.endpoint-comparison'
          : '';
    const descriptor = catalog.find((d) => d.id === experiment);
    if (!descriptor || !payload) throw new Error('지원하지 않는 이전 실험 설정입니다.');
    const parameters: Record<string, any> = {};
    for (const [key, spec] of Object.entries(descriptor.parameters)) {
      let v = payload[key];
      if (spec.type === 'boolean' && (v === '0' || v === '1')) v = v === '1';
      parameters[key] = v;
    }
    const draft = {
      id: payload.scenarioId,
      hypothesis: '기존 대시보드에서 가져온 설정 — 실행 전 가설을 확인하세요.',
      parameters,
    };
    draft.parameters = definition(descriptor, draft).parameters;
    result.entries.push({ experiment, draft });
  };
  for (const mode of ['queue', 'io']) if (dashboard[mode]) add(mode, dashboard[mode]);
  for (const h of history) add(h.mode, h.payload);
  result.legacyIo = read(legacyKeys[2], []);
  result.baseline = value.session.baseline || dashboard.browser?.compareA || '';
  result.candidate = value.session.candidate || dashboard.browser?.compareB || '';
  result.filter = dashboard.browser?.jobFilter || '';
  return validateSettings(result, catalog);
}
export function mergeSettings(current: Settings, incoming: Settings, replace: boolean): Settings {
  const entries = new Map(current.entries.map((e) => [e.experiment + ':' + e.draft.id, e]));
  for (const e of incoming.entries) {
    const key = e.experiment + ':' + e.draft.id;
    if (replace || !entries.has(key)) entries.set(key, e);
  }
  if (entries.size > 12) throw new Error('저장 설정은 최대 12개입니다. 가져올 항목을 줄이세요.');
  const merge = <T>(a: T[], b: T[], max: number) =>
    Array.from(new Map([...b, ...a].map((e) => [JSON.stringify(e), e])).values()).slice(0, max);
  return {
    version: 1,
    entries: [...entries.values()],
    echoes: merge(current.echoes, incoming.echoes, 20),
    legacyIo: merge(current.legacyIo, incoming.legacyIo, 12),
    baseline: replace
      ? incoming.baseline || current.baseline
      : current.baseline || incoming.baseline,
    candidate: replace
      ? incoming.candidate || current.candidate
      : current.candidate || incoming.candidate,
    filter: replace ? incoming.filter || current.filter : current.filter || incoming.filter,
  };
}
