import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
const evidence = JSON.parse(readFileSync('../contracts/dashboard/v1/fixtures/evidence.json', 'utf8'));

test('run history sends scenario and status filters to the server and keeps them when paging', async ({ page }) => {
  const requested: URLSearchParams[] = [];
  await page.route('**/api/runs?*', (r) => {
    const params = new URL(r.request().url()).searchParams;
    requested.push(params);
    const filtered = params.get('status') === 'FAILED';
    return r.fulfill({
      json: filtered
        ? { runs: [{ ...evidence.run, runId: 'failed-1', scenarioId: 'sweep-capacity.p1', status: 'FAILED', sequence: 9 }], nextBefore: 9 }
        : { runs: [], nextBefore: null },
    });
  });
  await page.goto('/app/runs');
  await page.getByLabel('시나리오 ID 시작').fill('sweep-capacity');
  await page.getByLabel('상태', { exact: true }).selectOption('FAILED');
  await page.getByRole('button', { name: '기록 조회' }).click();
  await expect(page.getByRole('link', { name: 'sweep-capacity.p1' })).toBeVisible();
  const last = requested.at(-1)!;
  expect(last.get('scenario')).toBe('sweep-capacity');
  expect(last.get('status')).toBe('FAILED');
  await expect(page.getByRole('link', { name: '이전 실행' })).toHaveAttribute('href', /scenario=sweep-capacity.*status=FAILED.*before=9/);
  await expect(page.getByRole('link', { name: '최신 실행' })).toHaveAttribute('href', '/app/runs?scenario=sweep-capacity&status=FAILED');
});
