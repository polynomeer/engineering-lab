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

test('a running series can be asked to stop before its next repetition', async ({ page }) => {
  const id = '11111111-2222-3333-4444-555555555555';
  const series = {
    seriesId: id, experimentType: 'concurrency.queue-contention', scenarioId: 'stop-me', definitionSha256: 'a'.repeat(64),
    repetitions: 10, warmup: 0, status: 'RUNNING', createdAtEpochMs: 1, completedAtEpochMs: 0, failureMessage: null, sequence: 1,
  };
  await page.route('**/api/run-series/' + id, (r) =>
    r.fulfill({ json: { series, members: [], measuredSucceeded: 0, measures: [], overlappingRunIds: [], stability: null } }),
  );
  let cancelled = false;
  await page.route('**/api/run-series/' + id + '/cancel', (r) => {
    cancelled = r.request().method() === 'POST';
    return r.fulfill({ status: 202, json: series });
  });
  await page.goto('/app/series/' + id);
  await page.getByRole('button', { name: '반복 측정 중지' }).click();
  await expect(page.getByText('중지 요청을 보냈습니다. 실행 중인 Run이 끝나면 멈춥니다.')).toBeVisible();
  expect(cancelled).toBe(true);
  await expect(page.getByRole('button', { name: '반복 측정 중지' })).toBeDisabled();
});

test('an Excel scenario uploads its workbook once and runs as a series from the form', async ({ page }) => {
  test.setTimeout(90000);
  await page.goto('/app/experiments?experiment=pipeline.excel-ingestion');
  await page.getByLabel('입력 워크북 (.xlsx)').setInputFiles('../experiments/pipeline/excel-ingestion/scenarios/fixtures/sample.xlsx');
  await page.getByLabel('시나리오 ID').fill('e2e-excel-series-' + Date.now());
  await page.getByLabel('가설').fill('반복마다 같은 워크북 복사본을 쓴다');
  await page.getByLabel('측정 반복 횟수').fill('3');
  await page.getByRole('button', { name: '반복 측정 실행 (3회)' }).click();
  await page.getByRole('link', { name: '접수된 반복 측정 보기' }).click();
  const members = page.getByRole('table', { name: '반복 구성' });
  await expect(members.getByRole('row')).toHaveCount(4, { timeout: 30000 });
  await expect(page.getByRole('table', { name: 'measure별 통계' })).toContainText('rows.inserted', { timeout: 30000 });
});

test('repeating a CHECK scenario shows each check\'s pass rate and flags mixed verdicts', async ({ page }) => {
  const id = '22222222-2222-3333-4444-555555555555';
  const series = {
    seriesId: id, experimentType: 'career.mcp-platform.sequence', scenarioId: 'checks-x5', definitionSha256: 'b'.repeat(64),
    repetitions: 5, warmup: 0, status: 'FAILED', createdAtEpochMs: 1, completedAtEpochMs: 2, failureMessage: '1 of 5 measured repetitions did not succeed', sequence: 2,
  };
  await page.route('**/api/run-series/' + id, (r) =>
    r.fulfill({
      json: {
        series, members: [], measuredSucceeded: 4, measures: [], overlappingRunIds: [], stability: null,
        checks: [{ checkId: 'no-overlap', runs: 5, passed: 4, failed: 1 }, { checkId: 'final-order', runs: 5, passed: 5, failed: 0 }],
      },
    }),
  );
  await page.route('**/api/run-series/' + id + '/regression?*', (r) => r.fulfill({ json: { overall: 'NO_BASELINE', baselineSeriesId: null, candidateSeriesId: id, tolerancePct: 5, measures: [] } }));
  await page.route('**/api/run-series/baselines?*', (r) => r.fulfill({ json: [] }));
  await page.goto('/app/series/' + id);
  const table = page.getByRole('table', { name: 'CHECK 안정성' });
  await expect(table.getByRole('row', { name: /no-overlap/ })).toContainText('4 / 5');
  await expect(table.getByRole('row', { name: /no-overlap/ })).toContainText('불안정');
  await expect(table.getByRole('row', { name: /final-order/ })).toContainText('항상 통과');
  await expect(page.getByRole('note', { name: '측정 환경 주의' })).toContainText('반복마다 판정이 달라진 CHECK: no-overlap');
});
