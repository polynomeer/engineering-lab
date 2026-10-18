import { test, expect } from '@playwright/test';

test('a repeated queue scenario runs as a series and summarizes measured repetitions', async ({ page }) => {
  await page.goto('/app/experiments?experiment=concurrency.queue-contention');
  await page.getByLabel('시나리오 ID').fill('e2e-series-' + Date.now());
  await page.getByLabel('측정 반복 횟수').fill('2');
  await page.getByLabel('warmup 횟수').fill('1');
  await page.getByRole('button', { name: '반복 측정 실행 (3회)' }).click();
  await page.getByRole('link', { name: '접수된 반복 측정 보기' }).click();
  await expect(page.getByRole('heading', { name: '반복 측정 상세', exact: true })).toBeVisible();
  const members = page.getByRole('table', { name: '반복 구성' });
  await expect(members.getByRole('row')).toHaveCount(4, { timeout: 30000 });
  await expect(members).toContainText('warmup · 통계 제외');
  const measures = page.getByRole('table', { name: 'measure별 통계' });
  await expect(measures).toContainText('throughput.itemsPerSec', { timeout: 30000 });
  await expect(measures).toContainText('n=2');
  await page.getByRole('link', { name: '반복 측정 목록' }).click();
  await expect(page.getByRole('table', { name: '반복 측정 · 최신순' })).toBeVisible();
});
