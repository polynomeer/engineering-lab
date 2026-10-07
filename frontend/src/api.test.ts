import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiUrl, get, optional, artifactReason, parseRoute, type Run, type Artifact } from './api';
import evidence from '../../contracts/dashboard/v1/fixtures/evidence.json';
afterEach(() => vi.unstubAllGlobals());
describe('API compatibility', () => {
  it('reads the shared backend fixture through the API prefix', async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(evidence.page), {
        headers: { 'content-type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetcher);
    expect(await get('/runs?limit=1')).toEqual(evidence.page);
    expect(fetcher.mock.calls[0][0]).toBe('/api/runs?limit=1');
  });
  it('falls back only on optional endpoint 404', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 404 })));
    expect(await optional('/dashboard/v1/capabilities')).toBeNull();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })));
    await expect(optional('/modules')).rejects.toThrow('503');
  });
  it('rejects HTML fallback and nonlocal API paths', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(new Response('<html/>', { headers: { 'content-type': 'text/html' } })),
    );
    await expect(get('/runs')).rejects.toThrow('JSON');
    expect(() => apiUrl('//other.example')).toThrow();
  });
  it('preserves encoded deep links and rejects malformed paths', () => {
    expect(parseRoute('/app/runs/' + evidence.run.runId, '').kind).toBe('detail');
    expect(parseRoute('/app/runs/%xx', '').kind).toBe('missing');
    expect(
      parseRoute('/app/experiments/pipeline.excel-ingestion', '?experiment=other').query.get(
        'experiment',
      ),
    ).toBe('pipeline.excel-ingestion');
    expect(parseRoute('/app/experiments/%xx', '').kind).toBe('missing');
    expect(parseRoute('/app/compare', '?baseline=a&candidate=b').query.get('candidate')).toBe('b');
  });
  it('does not offer downloads for arbitrary references, active runs or excessive sizes', () => {
    const run = evidence.run as Run;
    expect(artifactReason(run, evidence.artifacts[0], 1000)).toBe('참조 전용');
    const id = 'jfr_' + run.runId;
    const a: Artifact = {
      artifactId: id,
      relativePath: `jfr/${id}.jfr`,
      mediaType: 'application/octet-stream',
      byteSize: 123,
      sha256: 'a'.repeat(64),
    };
    expect(artifactReason(run, a, 1000)).toBeNull();
    expect(artifactReason({ ...run, status: 'RUNNING' }, a, 1000)).toContain('종료');
    expect(artifactReason(run, a, 100)).toContain('제한');
  });
});
