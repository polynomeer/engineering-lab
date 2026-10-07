import { expect, it } from 'vitest';
import {
  emptySettings,
  loadSettings,
  mergeSettings,
  parseTransfer,
  saveSettings,
  validateSettings,
  boundedJson,
  legacyKeys,
} from './settings';
import { presets } from './execution';
import type { Descriptor } from './api';
const queue: Descriptor = {
  id: 'concurrency.queue-contention',
  title: 'queue',
  category: 'CONCURRENCY',
  input: 'NONE',
  measurementModes: ['SINGLE_PASS'],
  parameters: Object.fromEntries(
    [
      'queueCapacity',
      'producerThreads',
      'consumerThreads',
      'itemsPerProducer',
      'consumerDelayMs',
    ].map((key) => [
      key,
      { type: 'integer', minimum: 0, maximum: 1000, values: [], unit: null, defaultValue: null },
    ]),
  ),
};
const entry = { experiment: queue.id, draft: presets[queue.id][0] };
it('imports legacy state and session selection without mutation', () => {
  const payload = {
    format: 'engineering-lab-legacy',
    version: 1,
    local: {
      [legacyKeys[0]]: JSON.stringify({
        queue: { scenarioId: 'old', ...entry.draft.parameters },
        browser: { compareA: 'fallback', jobFilter: queue.id },
      }),
    },
    session: { baseline: 'session', candidate: 'candidate' },
  };
  const raw = JSON.stringify(payload),
    imported = parseTransfer(raw, [queue]);
  expect(imported.entries[0].draft.id).toBe('old');
  expect(imported.baseline).toBe('session');
  expect(imported.filter).toBe(queue.id);
  expect(JSON.stringify(payload)).toBe(raw);
});
it('rejects size, count, unknown versions and invalid values before persistence', () => {
  expect(() => boundedJson('x'.repeat(1048577))).toThrow('1 MiB');
  expect(() =>
    parseTransfer(JSON.stringify({ ...emptySettings(), entries: Array(101).fill(entry) }), [queue]),
  ).toThrow('100');
  expect(() => validateSettings({ ...emptySettings(), version: 2 })).toThrow('버전');
  expect(() =>
    parseTransfer(
      JSON.stringify({
        ...emptySettings(),
        entries: [
          {
            ...entry,
            draft: {
              ...entry.draft,
              parameters: { ...entry.draft.parameters, queueCapacity: 9999 },
            },
          },
        ],
      }),
      [queue],
    ),
  ).toThrow('queueCapacity');
  expect(() => validateSettings({ ...emptySettings(), legacyIo: [{ pinning: 'false' }] })).toThrow(
    'boolean',
  );
  expect(() => validateSettings({ ...emptySettings(), baseline: '../bad' })).toThrow('ID');
});
it('merges only selected entries with explicit conflict policy and bounded histories', () => {
  const current = { ...emptySettings(), entries: [entry] };
  const replacement = { ...entry, draft: { ...entry.draft, hypothesis: 'replacement' } };
  expect(
    mergeSettings(current, { ...emptySettings(), entries: [replacement] }, false).entries[0],
  ).toEqual(entry);
  expect(
    mergeSettings(current, { ...emptySettings(), entries: [replacement] }, true).entries[0],
  ).toEqual(replacement);
  const entries = Array.from({ length: 13 }, (_, i) => ({
    ...entry,
    draft: { ...entry.draft, id: 'id-' + i },
  }));
  expect(() => mergeSettings(emptySettings(), { ...emptySettings(), entries }, false)).toThrow(
    '12',
  );
});
it('uses one atomic write and retains history when updating presets', () => {
  let raw = '';
  let writes = 0;
  const storage = {
    getItem: () => raw,
    setItem: (_: string, value: string) => {
      writes++;
      raw = value;
    },
  };
  saveSettings({ ...emptySettings(), entries: [entry], baseline: 'run-a' }, storage);
  expect(writes).toBe(1);
  expect(loadSettings(storage).baseline).toBe('run-a');
  expect(() =>
    saveSettings(emptySettings(), {
      setItem: () => {
        throw new Error('quota');
      },
    }),
  ).toThrow('quota');
  expect(loadSettings(storage).entries).toEqual([entry]);
});
it('keeps legacy comparison averages separate from single request observations', () => {
  const legacyIo = [
    {
      msg: 'old',
      delayMs: 10,
      pinning: false,
      pinDelayMs: 20,
      virtualAvgMs: 30,
      reactiveAvgMs: 40,
      winner: 'Virtual',
    },
  ];
  const result = parseTransfer(
    JSON.stringify({
      format: 'engineering-lab-legacy',
      version: 1,
      local: { [legacyKeys[2]]: JSON.stringify(legacyIo) },
      session: {},
    }),
    [queue],
  );
  expect(result.legacyIo).toEqual(legacyIo);
  expect(result.echoes).toEqual([]);
});
