import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
test('deployment redirects old screen addresses with their queries, keeps API status and exact built assets', async ({
  request,
  page,
}) => {
  const origin = new URL(process.env.API_URL ?? 'http://127.0.0.1:18080').origin;
  const redirect = process.env.EXPECT_REDIRECT === '1';
  for (const [path, destination] of [
    ['/experiments/ui', '/app/experiments'],
    ['/ingest/ui', '/app/experiments/pipeline.excel-ingestion'],
    ['/ingest/ui/db', '/app/tools/ingestion-db'],
    ['/lab/io/ui', '/app/tools/io-echo'],
    ['/runs/ui?run=run-1', '/app/runs/run-1'],
    ['/runs/ui?baseline=a&candidate=b', '/app/compare'],
    ['/runs/ui?experiment=io.endpoint-comparison&before=7', '/app/runs'],
    ['/ingest/ui/run-1', '/app/runs/run-1'],
    ['/experiments/ui/run-1', '/app/runs/run-1'],
  ]) {
    const url = new URL(path, origin);
    url.searchParams.set('msg', 'a & 한글');
    const response = await request.get(url.href, { maxRedirects: 0 });
    // Without an embedded dashboard or frontend-origin there is no destination: the screens are gone.
    expect(response.status()).toBe(redirect ? 302 : 404);
    if (redirect) {
      const location = new URL(response.headers().location, origin);
      expect(location.pathname).toBe(destination);
      expect(location.searchParams.get('msg')).toBe('a & 한글');
      for (const key of ['baseline', 'candidate', 'experiment', 'before'])
        if (url.searchParams.has(key))
          expect(location.searchParams.get(key)).toBe(url.searchParams.get(key));
    }
    // Old bookmarks with the removed escape hatch land on the dashboard too, without the parameter.
    url.searchParams.set('legacy', 'true');
    const bookmarked = await request.get(url.href, { maxRedirects: 0 });
    expect(bookmarked.status()).toBe(redirect ? 302 : 404);
    if (redirect) expect(new URL(bookmarked.headers().location, origin).searchParams.has('legacy')).toBe(false);
  }
  expect((await request.get('/api/runs/ui/options')).headers()['content-type']).toContain(
    'application/json',
  );
  const invalid = await request.post('/api/runs', { data: {} });
  expect(invalid.status()).toBe(400);
  expect(invalid.headers().location).toBeUndefined();
  expect((await request.get('/api/not-a-route')).status()).toBe(404);
  // Build parity is a deployment-only check, enabled for both packaged modes.
  if (process.env.VERIFY_BUILD === '1') {
    const html = await (await request.get('/app/runs/deep-link')).text();
    const asset = html.match(/src="([^"]+\.js)"/)![1];
    expect(await (await request.get(asset)).body()).toEqual(
      await readFile('dist/' + asset.slice('/app/'.length)),
    );
    expect((await request.get('/app/assets/missing.js')).status()).toBe(404);
  }
  await page.goto('/app/experiments/pipeline.excel-ingestion?msg=kept');
  await expect(page.getByLabel('XLSX 파일')).toBeVisible();
  await page.getByRole('link', { name: '실행 기록', exact: true }).click();
  await expect(page.getByRole('heading', { name: '실행 기록', exact: true })).toBeVisible();
  await page.goBack();
  await expect(page.getByLabel('XLSX 파일')).toBeVisible();
  await page.goForward();
  await expect(page.getByRole('heading', { name: '실행 기록', exact: true })).toBeVisible();
});
