import { test, expect } from '@playwright/test';
import { docShot } from './docShot';

test('a one-parameter sweep runs a series per value and charts the medians', async ({ page }, info) => {
  test.setTimeout(120000);
  await page.goto('/app/experiments?experiment=concurrency.queue-contention');
  await page.getByLabel('시나리오 ID').fill('e2e-sweep-' + Date.now());
  await page.getByLabel('측정 반복 횟수').fill('3');
  await page.getByLabel('스윕 파라미터 1').selectOption('queueCapacity');
  await page.getByLabel('스윕 값 1').fill('1, 8');
  await page.getByRole('button', { name: '스윕 실행 (2개 점 × 3회)' }).click();
  await page.getByRole('link', { name: '접수된 스윕 보기' }).click();
  await expect(page.getByRole('heading', { name: '파라미터 스윕', exact: true })).toBeVisible();
  const points = page.getByRole('table', { name: '스윕 점' });
  await expect(points.getByRole('row')).toHaveCount(3, { timeout: 60000 });
  await expect(points).toContainText('queueCapacity=8');
  await expect(page.getByRole('img', { name: /queueCapacity별 .* 중앙값/ })).toBeVisible({ timeout: 60000 });
  await expect(points.getByRole('cell', { name: '성공' })).toHaveCount(2, { timeout: 60000 });
  await page.screenshot({ path: docShot(info, '33-sweep.png'), fullPage: true });
  await page.goto('/app/series');
  await expect(page.getByRole('table', { name: '파라미터 스윕 · 최신순' })).toBeVisible();
});

test('a two-parameter sweep renders a median heatmap', async ({ page }) => {
  const stats = (median: number) => ({
    count: 3, min: median - 1, max: median + 1, mean: median, median, p95: median + 1,
    standardDeviation: 1, coefficientOfVariation: 0.01,
  });
  const point = (position: number, a: number, b: string, median: number) => ({
    position,
    parameters: { queueCapacity: a, mode: b },
    series: {
      series: { seriesId: 's' + position, status: 'SUCCEEDED', scenarioId: 'grid.p' + position, repetitions: 3, warmup: 0 },
      members: [],
      measuredSucceeded: 3,
      measures: [{ role: 'throughput', name: 'throughput.itemsPerSec', unit: 'items/s', kind: 'gauge', statistics: stats(median) }],
    },
  });
  await page.route('**/api/run-sweeps/grid', (r) =>
    r.fulfill({
      json: {
        sweep: {
          sweepId: 'grid', experimentType: 'concurrency.queue-contention', scenarioId: 'grid', status: 'SUCCEEDED',
          grid: { queueCapacity: [1, 8], mode: ['A', 'B'] }, repetitions: 3, warmup: 0, failureMessage: null,
        },
        points: [point(0, 1, 'A', 100), point(1, 1, 'B', 150), point(2, 8, 'A', 300), point(3, 8, 'B', 420)],
      },
    }),
  );
  await page.goto('/app/sweeps/grid');
  const heatmap = page.getByRole('table', { name: 'queueCapacity × mode throughput 중앙값 (items/s)' });
  await expect(heatmap).toBeVisible();
  await expect(heatmap.getByRole('row')).toHaveCount(3);
  await expect(heatmap).toContainText('420');
});
