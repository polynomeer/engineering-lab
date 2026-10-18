import { test, expect } from '@playwright/test';
import { docShot } from './docShot';
import { readFileSync } from 'node:fs';
const evidence = JSON.parse(
  readFileSync('../contracts/dashboard/v1/fixtures/evidence.json', 'utf8'),
);

test('metric graphs show recorded pipeline rates, occupancy, keyboard values and bounded page windows', async ({
  page,
}, info) => {
  const id = 'chart-pipeline';
  await page.route('**/api/experiments', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/modules', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/runs/ui/options', (r) => r.fulfill({ json: { pageSize: 20 } }));
  await page.route('**/api/dashboard/v1/capabilities', (r) =>
    r.fulfill({
      json: { schemaVersion: 1, features: { executionEvidence: true, extendedExport: true } },
    }),
  );
  await page.route('**/api/dashboard/v1/runs/' + id + '/execution', (r) =>
    r.fulfill({
      json: {
        available: true,
        execution: {
          definition: {
            kind: 'IN_PROCESS',
            moduleId: 'pipeline',
            measurementScope: 'CONTROLLER_PROCESS',
          },
          phase: 'FINALIZING',
          evidenceComplete: true,
          worker: null,
        },
      },
    }),
  );
  await page.route('**/api/runs?*', (r) => r.fulfill({ json: { runs: [], nextBefore: null } }));
  await page.route('**/api/runs/' + id, (r) =>
    r.fulfill({
      json: {
        ...evidence.run,
        runId: id,
        experimentType: 'pipeline.excel-ingestion',
        scenarioId: 'Pipeline · 그래프 검증',
        startedAtEpochMs: 1000,
        completedAtEpochMs: 8000,
        runMetadata: {
          ...evidence.run.runMetadata,
          parameters: {
            rawCapacity: '1024',
            mappedCapacity: '512',
            'scenario.hypothesis': '검증용 데이터 · 실제 표본의 처리량과 점유율 표시',
          },
        },
        summary: { counters: { 'rows.inserted': 35000 }, gauges: {}, details: {} },
      },
    }),
  );
  await page.route('**/api/runs/' + id + '/artifacts', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/runs/' + id + '/metrics?*', (r) => {
    const second = new URL(r.request().url()).searchParams.get('after') === '4';
    const start = second ? 5 : 0;
    return r.fulfill({
      json: {
        metrics: Array.from({ length: second ? 3 : 5 }, (_, j) => {
          const i = start + j;
          return {
            sequence: i,
            point: {
              timestampEpochMs: 1000 + i * 1000,
              counters: {
                'time.elapsedMs': i * 1000,
                'throughput.producedPerSec': 1000 + i * 120,
                'throughput.mappedPerSec': 900 + i * 110,
                'throughput.insertedPerSec': i === 2 ? null : 800 + i * 100,
              },
              gauges: {
                'queue.raw.saturationPct': 20 + i * 8,
                'queue.mapped.saturationPct': 10 + i * 5,
              },
              labels: { source: 'pipeline.progressSnapshot' },
            },
          };
        }),
        nextAfter: second ? null : 4,
      },
    });
  });
  await page.goto('/app/runs/' + id);
  await page.getByRole('button', { name: '메트릭', exact: true }).click();
  const rate = page.getByRole('img', { name: '처리량 추이', exact: true });
  const queue = page.getByRole('img', { name: '큐 점유율', exact: true });
  await expect(rate).toBeVisible();
  await expect(queue).toBeVisible();
  await expect(rate.locator('path').nth(2)).toHaveAttribute('d', /M.*L.*M.*L/);
  const slider = page.getByRole('slider', { name: '처리량 추이 관측 지점 선택' });
  await slider.focus();
  await page.keyboard.press('Home');
  await expect(slider).toHaveValue('0');
  await expect(slider).toHaveAttribute('aria-valuetext', /Parse: 1,000 rows\/s/);
  await expect(
    page.getByRole('region', { name: '처리량 추이', exact: true }).locator('output'),
  ).toContainText('Parse: 1,000 rows/s');
  await page.getByText('큐 점유율 값 표', { exact: true }).click();
  await expect(page.getByRole('table', { name: '큐 점유율 그래프 값' })).toBeVisible();
  await page.getByText('큐 점유율 값 표', { exact: true }).click();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: docShot(info, '22-metric-charts-desktop.png'), fullPage: true });
  for (const width of [768, 375]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await expect(rate).toBeVisible();
  }
  await page.locator('.navigation-drawer > summary').click();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: docShot(info, '23-metric-charts-mobile.png'), fullPage: true });
  await page.getByRole('button', { name: '다음 관측', exact: true }).click();
  await expect(page.getByText('현재 관측 페이지', { exact: false })).toContainText('3개 표본');
  await expect(slider).toHaveAttribute('max', '2');
  await expect(
    page.getByRole('region', { name: '처리량 추이', exact: true }).locator('output'),
  ).toContainText('#7');
});

test('queue rate is an interval calculation and absent capacity never becomes zero occupancy', async ({
  page,
}) => {
  const id = 'chart-queue';
  await page.route('**/api/experiments', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/modules', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/runs/ui/options', (r) => r.fulfill({ json: { pageSize: 20 } }));
  await page.route('**/api/dashboard/v1/capabilities', (r) => r.fulfill({ status: 404 }));
  await page.route('**/api/runs/' + id, (r) =>
    r.fulfill({
      json: {
        ...evidence.run,
        runId: id,
        startedAtEpochMs: 1000,
        runMetadata: { ...evidence.run.runMetadata, parameters: {} },
      },
    }),
  );
  await page.route('**/api/runs/' + id + '/artifacts', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/runs/' + id + '/metrics?*', (r) =>
    r.fulfill({
      json: {
        metrics: [0, 1].map((i) => ({
          sequence: i,
          point: {
            timestampEpochMs: 1000 + i * 1000,
            counters: { 'items.consumed': i * 10 },
            gauges: { 'queue.depth': i },
            labels: { experiment: 'queue-contention' },
          },
        })),
        nextAfter: null,
      },
    }),
  );
  await page.goto('/app/runs/' + id);
  await expect(page.getByRole('img', { name: '처리량 추이', exact: true })).toBeVisible();
  await expect(
    page.getByRole('region', { name: '처리량 추이', exact: true }).locator('output'),
  ).toContainText('10 items/s');
  await expect(page.getByRole('img', { name: '큐 점유율', exact: true })).toHaveCount(0);
  await expect(page.getByRole('region', { name: '큐 점유율', exact: true })).toContainText(
    '그래프로 표시할 값이 없습니다',
  );
  await expect(
    page.getByText('유효한 값이 한 개여서 점으로 표시합니다.', { exact: false }),
  ).toBeVisible();
});

test('active graphs follow new sequences, retain data on failure and drain final observations', async ({ page }, info) => {
  const id = 'live-queue';
  let status = 'RUNNING';
  let count = 3;
  let fail = false;
  const cursors: number[] = [];
  await page.route('**/api/experiments', r => r.fulfill({ json: [] }));
  await page.route('**/api/modules', r => r.fulfill({ json: [] }));
  await page.route('**/api/runs/ui/options', r => r.fulfill({ json: { pageSize: 20 } }));
  await page.route('**/api/dashboard/v1/capabilities', r => r.fulfill({ status: 404 }));
  await page.route('**/api/runs/' + id, r => r.fulfill({ json: {
    ...evidence.run, runId: id, status, startedAtEpochMs: 1000,
    runMetadata: { ...evidence.run.runMetadata, parameters: { queueCapacity: '4' } },
  } }));
  await page.route('**/api/runs/' + id + '/artifacts', r => r.fulfill({ json: [] }));
  await page.route('**/api/runs/' + id + '/metrics?*', r => {
    const after = Number(new URL(r.request().url()).searchParams.get('after'));
    cursors.push(after);
    if (fail && after >= 2) return r.fulfill({ status: 503, body: 'temporarily unavailable' });
    return r.fulfill({ json: { metrics: Array.from({ length: count }, (_, i) => ({
      sequence: i, point: { timestampEpochMs: 1000 + i * 250,
        counters: { 'items.consumed': i * 10, 'time.elapsedMs': i * 250 },
        gauges: { 'queue.depth': i % 5 }, labels: {} },
    })).filter(row => row.sequence > after), nextAfter: null } });
  });
  await page.goto('/app/runs/' + id);
  await page.getByRole('button', { name: '메트릭', exact: true }).click();
  const slider = page.getByRole('slider', { name: '처리량 추이 관측 지점 선택' });
  await expect(slider).toHaveAttribute('max', '2');
  fail = true;
  await expect(page.locator('.metric-charts [role="alert"]')).toBeVisible({ timeout: 7000 });
  await expect(slider).toHaveAttribute('max', '2');
  fail = false; count = 7;
  await page.getByRole('button', { name: '메트릭 다시 조회', exact: true }).click();
  await expect(slider).toHaveAttribute('max', '6');
  expect(cursors).toContain(2);
  count = 9; status = 'SUCCEEDED';
  await expect(page.getByText('수집 완료 · 최종 구간', { exact: true })).toBeVisible({ timeout: 8000 });
  await expect(slider).toHaveAttribute('max', '8');
  await expect(page.getByRole('img', { name: '처리량 추이', exact: true }).locator('path').first()).toHaveAttribute('d', /M.*L.*L/);
  await page.screenshot({ path: docShot(info, '24-live-metric-charts.png'), fullPage: true });
  const finalReads = cursors.filter(cursor => cursor >= 8).length;
  await page.waitForTimeout(2300);
  expect(cursors.filter(cursor => cursor >= 8)).toHaveLength(finalReads);
  await page.getByRole('button', { name: '현재 관측 페이지 보기', exact: true }).click();
  await expect(page.getByText('현재 관측 페이지', { exact: false })).toContainText('9개 표본');
});
