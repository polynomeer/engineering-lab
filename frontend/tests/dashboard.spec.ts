import { test, expect } from '@playwright/test';
const definition = (id: string, capacity: number) => ({
  schemaVersion: 1,
  experiment: 'concurrency.queue-contention',
  scenario: { id, hypothesis: 'Small queues make producer waiting observable' },
  parameters: {
    queueCapacity: capacity,
    producerThreads: 1,
    consumerThreads: 1,
    itemsPerProducer: 20,
    consumerDelayMs: 1,
  },
  measurement: { mode: 'SINGLE_PASS' },
});
test('real API: list, detail, comparison, export, deep links and mobile layout', async ({
  page,
  request,
}, testInfo) => {
  const ids: string[] = [];
  for (const [name, capacity] of [
    ['dashboard-baseline', 2],
    ['dashboard-candidate', 4],
  ] as const) {
    const response = await request.post('/api/runs', { data: definition(name, capacity) });
    expect(response.status()).toBe(202);
    const { runId } = await response.json();
    ids.push(runId);
    expect(response.headers().location).toBe('/api/runs/' + runId);
    await expect
      .poll(async () => {
        const r = await request.get('/api/runs/' + runId);
        return (await r.json()).status;
      })
      .toBe('SUCCEEDED');
  }
  await page.goto('/app/runs');
  await expect(page.getByRole('heading', { name: '실행 기록', exact: true })).toBeVisible();
  if (process.env.EXPECT_LEGACY === '1')
    await expect(page.getByText('기존 API 호환 모드', { exact: false })).toBeVisible();
  else await expect(page.getByText('기존 API 호환 모드', { exact: false })).toHaveCount(0);
  await page.getByRole('link', { name: 'dashboard-baseline', exact: true }).first().click();
  await expect(
    page.getByRole('heading', { name: 'dashboard-baseline', exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('cell', { name: '20', exact: true }).first()).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole('heading', { name: 'dashboard-baseline', exact: true }),
  ).toBeVisible();
  const download = page.waitForEvent('download');
  await page.getByRole('link', { name: '증거 내보내기 (NDJSON)' }).click();
  expect((await download).suggestedFilename()).toBe('run-evidence.ndjson');
  const exported = await request.get('/api/runs/' + ids[0] + '/export');
  expect(exported.headers()['content-type']).toContain('application/x-ndjson');
  expect(JSON.parse((await exported.text()).trim().split('\n').at(-1)!).type).toBe('end');
  await page.goto('/app/compare?baseline=' + ids[0] + '&candidate=' + ids[1]);
  await expect(page.getByRole('table', { name: '기록된 조건의 차이' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '다름', exact: true }).first()).toBeVisible();
  await page.screenshot({
    path:
      process.env.DOC_SCREENSHOTS === '1'
        ? '../docs/images/10-standalone-comparison.png'
        : testInfo.outputPath('comparison.png'),
    fullPage: true,
  });
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto('/app/runs/' + ids[0]);
  await expect(
    page.getByRole('heading', { name: 'dashboard-baseline', exact: true }),
  ).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.screenshot({
    path:
      process.env.DOC_SCREENSHOTS === '1'
        ? '../docs/images/11-standalone-mobile.png'
        : testInfo.outputPath('mobile.png'),
    fullPage: true,
  });
  await page.goto('/app/runs/not-found');
  await expect(page.getByRole('alert')).toContainText('404');
  const unknown = await request.get('/api/nonexistent-route');
  expect(unknown.status()).toBe(404);
  expect(unknown.headers()['content-type']).not.toContain('text/html');
});
test('legacy catalog fallback, empty pages and keyboard navigation', async ({ page }) => {
  await page.route('**/api/dashboard/v1/capabilities', (route) => route.fulfill({ status: 404, body: '' }));
  await page.route('**/api/modules', (route) => route.fulfill({ status: 404, body: '' }));
  await page.route('**/api/runs?*', (route) =>
    route.fulfill({ json: { runs: [], nextBefore: null } }),
  );
  await page.goto('/app/runs');
  await expect(
    page.getByText('이 페이지에 저장된 실행이 없습니다.', { exact: false }),
  ).toBeVisible();
  await expect(page.getByRole('navigation', { name: '대시보드 탐색' }).getByRole('link', { name: '분야별 카탈로그', exact: true })).toBeVisible();
  await expect(page.getByText('기존 API 호환 모드', { exact: false })).toBeVisible();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: '본문으로 이동' })).toBeFocused();
});
test('server errors stay visible and a metric cursor of zero is followed', async ({
  page,
  request,
}) => {
  const response = await request.post('/api/runs', { data: definition('cursor-boundary', 2) });
  expect(response.status()).toBe(202);
  const { runId } = await response.json();
  await expect
    .poll(async () => {
      const result = await request.get('/api/runs/' + runId);
      return (await result.json()).status;
    })
    .toBe('SUCCEEDED');
  await page.route('**/api/modules', (route) => route.fulfill({ status: 503, body: '' }));
  await page.goto('/app/runs');
  await expect(page.getByRole('alert')).toContainText('503');
  await page.unroute('**/api/modules');
  await page.route('**/api/runs/*/metrics?*', (route) => {
    const after = new URL(route.request().url()).searchParams.get('after');
    return route.fulfill({
      json: after === '-1' ? { metrics: [], nextAfter: 0 } : { metrics: [], nextAfter: null },
    });
  });
  await page.goto('/app/runs/' + runId);
  await expect(page.getByRole('button', { name: '다음 관측' })).toBeVisible();
  const cursorRequest = page.waitForRequest((r) => r.url().includes('/metrics?after=0&'));
  await page.getByRole('button', { name: '다음 관측' }).click();
  await cursorRequest;
  await expect(page.getByRole('button', { name: '다음 관측' })).toHaveCount(0);
});

test('unknown capability version falls back and real API failures remain visible', async ({ page }) => {
  await page.route('**/api/dashboard/v1/capabilities', route => route.fulfill({ json: { schemaVersion: 99, features: { localJvm: true } } }));
  await page.goto('/app/runs');
  await expect(page.getByText('기존 API 호환 모드', { exact: false })).toBeVisible();
  await page.unroute('**/api/dashboard/v1/capabilities');
  await page.route('**/api/dashboard/v1/capabilities', route => route.fulfill({ status: 503, body: 'Unavailable' }));
  await page.reload();
  await expect(page.getByRole('alert').first()).toContainText('503');
});

test('extension catalog and legacy comparison work together against the new server', async ({ page, request }, info) => {
  test.skip(process.env.EXPECT_LEGACY === '1', 'Extension endpoints are absent on the legacy server');
  const caps = await (await request.get('/api/dashboard/v1/capabilities')).json();
  expect(caps.schemaVersion).toBe(1);
  expect(caps.features.localJvm).toBe(false);
  expect(caps.features.executionEvidence).toBe(true);
  const modules = await (await request.get('/api/dashboard/v1/modules')).json();
  expect(modules.map((m: { module: { id: string } }) => m.module.id)).toEqual(
    expect.arrayContaining(['pipeline', 'concurrency', 'io']),
  );
  expect(modules.every((m: { readiness: string }) => m.readiness === 'READY')).toBe(true);
  const extension = await (await request.get('/api/dashboard/v1/experiments/concurrency.queue-contention')).json();
  expect(extension.moduleId).toBe('concurrency');
  const response = await request.post('/api/runs', { data: definition('extension-compatibility', 2) });
  const { runId } = await response.json();
  await expect.poll(async () => (await (await request.get('/api/runs/' + runId)).json()).status).toBe('SUCCEEDED');
  const evidence = await (await request.get('/api/dashboard/v1/runs/' + runId + '/execution')).json();
  expect(evidence.execution.evidenceComplete).toBe(true);
  const origin = new URL(process.env.LEGACY_URL ?? 'http://127.0.0.1:18080').origin;
  await page.goto(origin + '/runs/ui?legacy=true&baseline=' + runId + '&candidate=' + runId);
  await expect(page.getByRole('rowheader', { name: 'execution.definition', exact: true })).toBeVisible();
  await page.getByRole('rowheader', { name: 'execution.definition', exact: true }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: process.env.CAPABILITY_SCREENSHOT === '1' ? '../docs/images/15-extension-legacy-comparison.png' : info.outputPath('legacy-extension.png'), fullPage: false });
});
