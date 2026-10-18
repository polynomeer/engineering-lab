import { test, expect } from '@playwright/test';
import { docShot } from './docShot';

test('a repeated queue scenario runs as a series, becomes a baseline, and judges the next series', async ({
  page,
  request,
}, info) => {
  test.setTimeout(90000);
  const scenario = 'e2e-series-' + Date.now();
  await page.goto('/app/experiments?experiment=concurrency.queue-contention');
  await page.getByLabel('시나리오 ID').fill(scenario);
  await page.getByLabel('측정 반복 횟수').fill('3');
  await page.getByLabel('warmup 횟수').fill('1');
  await page.getByRole('button', { name: '반복 측정 실행 (4회)' }).click();
  await page.getByRole('link', { name: '접수된 반복 측정 보기' }).click();
  await expect(page.getByRole('heading', { name: '반복 측정 상세', exact: true })).toBeVisible();
  const members = page.getByRole('table', { name: '반복 구성' });
  await expect(members.getByRole('row')).toHaveCount(5, { timeout: 30000 });
  await expect(members).toContainText('warmup · 통계 제외');
  const measures = page.getByRole('table', { name: 'measure별 통계' });
  await expect(measures).toContainText('throughput.itemsPerSec', { timeout: 30000 });
  await expect(measures).toContainText('n=3');

  await expect(page.getByText('기준선 없음', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '이 묶음을 기준선으로 지정' }).click();
  await expect(page.getByText('이 묶음이 현재 기준선입니다.')).toBeVisible();
  const baselineUrl = page.url();

  // The next series of the same scenario is judged against the pinned one.
  const definition = await request.get(
    '/api/runs/' + (await members.getByRole('link').nth(1).textContent())!.trim(),
  );
  const submitted = await request.post('/api/run-series?repetitions=3&warmup=0', {
    data: JSON.parse((await definition.json()).runMetadata.parameters['scenario.definition']),
  });
  expect(submitted.status()).toBe(202);
  await page.goto('/app/series/' + (await submitted.json()).seriesId);
  await expect(page.getByRole('table', { name: '기준선 대비 판정' })).toBeVisible({ timeout: 30000 });
  await expect(page.getByRole('link', { name: '기준선 묶음 보기' })).toHaveAttribute(
    'href',
    new URL(baselineUrl).pathname,
  );
  await page.screenshot({ path: docShot(info, '32-series-baseline.png'), fullPage: true });
  await page.getByRole('link', { name: '반복 측정 목록' }).click();
  await expect(page.getByRole('table', { name: '반복 측정 · 최신순' })).toBeVisible();
});
