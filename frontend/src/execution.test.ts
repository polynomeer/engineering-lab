import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  comparisonCsv,
  csv,
  definition,
  initialDraft,
  readSaved,
  submitRun,
  supportsForm,
} from './execution';
import type { Comparison, Descriptor } from './api';
const descriptor: Descriptor = {
  id: 'fixture.new-module',
  title: 'New module',
  category: 'FIXTURE',
  input: 'NONE',
  measurementModes: ['SINGLE_PASS'],
  parameters: {
    count: { type: 'integer', minimum: 1, maximum: 9, values: [], unit: 'items', defaultValue: 3 },
    enabled: {
      type: 'boolean',
      minimum: null,
      maximum: null,
      values: [],
      unit: null,
      defaultValue: null,
    },
    mode: {
      type: 'string',
      minimum: null,
      maximum: null,
      values: ['A', 'B'],
      unit: null,
      defaultValue: null,
    },
  },
};
const valid = { ...initialDraft(descriptor), hypothesis: 'Test schema-derived form' };
afterEach(() => vi.unstubAllGlobals());
describe('schema driven execution', () => {
  it('supports future modules and validates boundary values and typed booleans', () => {
    expect(supportsForm(descriptor)).toBe(true);
    expect(supportsForm({ ...descriptor, input: 'XLSX' })).toBe(false);
    expect(definition(descriptor, valid).parameters).toEqual({
      count: 3,
      enabled: false,
      mode: 'A',
    });
    for (const count of ['', ' ', '1.5', 10, false, Infinity])
      expect(() =>
        definition(descriptor, { ...valid, parameters: { ...valid.parameters, count } }),
      ).toThrow('count');
    expect(() =>
      definition(descriptor, { ...valid, parameters: { ...valid.parameters, enabled: 'false' } }),
    ).toThrow('enabled');
    expect(() => definition(descriptor, { ...valid, id: '../run' })).toThrow('ID');
    expect(() => definition(descriptor, { ...valid, hypothesis: ' ' })).toThrow('가설');
  });
  it('bounds and validates stored presets while tolerating removed modules', () => {
    const getItem = () =>
      JSON.stringify({
        version: 1,
        entries: [
          { experiment: descriptor.id, draft: valid },
          { experiment: descriptor.id, draft: { ...valid, id: '..' } },
          { experiment: 'removed', draft: valid },
        ],
      });
    expect(readSaved({ getItem }, [descriptor])).toEqual([
      { experiment: descriptor.id, draft: valid },
    ]);
    expect(() => readSaved({ getItem: () => 'x'.repeat(1024 * 1024 + 1) }, [])).toThrow('제한');
    expect(() =>
      readSaved(
        { getItem: () => JSON.stringify({ version: 1, entries: Array(101).fill(null) }) },
        [],
      ),
    ).toThrow('형식');
    expect(() =>
      readSaved(
        {
          getItem: () => {
            throw new Error('storage denied');
          },
        },
        [],
      ),
    ).toThrow('storage denied');
  });
  it('never retries ambiguous submissions and distinguishes rejection', async () => {
    const fetch = vi.fn().mockRejectedValue(new Error('network'));
    vi.stubGlobal('fetch', fetch);
    await expect(submitRun(definition(descriptor, valid))).rejects.toThrow('접수 여부');
    expect(fetch).toHaveBeenCalledTimes(1);
    fetch.mockResolvedValue(new Response('{}', { status: 400 }));
    await expect(submitRun(definition(descriptor, valid))).rejects.toThrow('거절');
    fetch.mockResolvedValue(new Response('{}', { status: 202 }));
    await expect(submitRun(definition(descriptor, valid))).rejects.toThrow('접수 여부');
    fetch.mockResolvedValue(new Response('{"runId":"accepted"}', { status: 202 }));
    await expect(submitRun(definition(descriptor, valid))).resolves.toBe('accepted');
  });
  it('exports missing values as empty cells with real line breaks and escaped text', () => {
    expect(csv([['a,"b"\nc', null, 0, -2, '=SUM(A1)', '한글']])).toBe(
      '\ufeff"a,""b""\nc","","0","-2","\'=SUM(A1)","한글"',
    );
    const comparison = {
      baseline: { runId: 'a', status: 'SUCCEEDED' },
      candidate: { runId: 'b', status: 'FAILED' },
      cautions: ['Different environment'],
      conditions: [],
      values: [{ kind: 'gauge', name: 'latency', unit: 'ms', baseline: 0, candidate: null }],
    } as unknown as Comparison;
    expect(comparisonCsv(comparison)).toContain('"gauge","latency","ms","0",""');
    expect(comparisonCsv(comparison)).toContain('\r\n"caution"');
  });
});
