import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// Recorded actual container/API results; opt in after equityContainerTest.
for (const name of ['deletion-before', 'deletion-after', 'redis-lock-before', 'redis-lock-after',
  'registration-before', 'registration-after', 'registration-invalid-row']) {
  test('actual equity snapshot: ' + name, async ({ page }, info) => {
    test.skip(!process.env.EQUITY_EVIDENCE, 'Run equityContainerTest and set EQUITY_EVIDENCE');
    const snapshot = JSON.parse(readFileSync(resolve(process.env.EQUITY_EVIDENCE!, name + '.json'), 'utf8'));
    const id = snapshot.run.runId;
    await page.route('**/api/experiments', r => r.fulfill({ json: [] }));
    await page.route('**/api/modules', r => r.fulfill({ json: [] }));
    await page.route('**/api/runs/ui/options', r => r.fulfill({ json: { pageSize: 20, artifactDownloadMaxBytes: 1048576 } }));
    await page.route('**/api/dashboard/v1/capabilities', r => r.fulfill({ json: { schemaVersion: 1,
      features: { container: true, executionEvidence: true, checksRead: true, extendedExport: true } } }));
    await page.route('**/api/runs/' + id, r => r.fulfill({ json: snapshot.run }));
    await page.route('**/api/runs/' + id + '/metrics?*', r => r.fulfill({ json: { ...snapshot.metrics, nextAfter: null } }));
    await page.route('**/api/runs/' + id + '/artifacts', r => r.fulfill({ json: snapshot.artifacts }));
    await page.route('**/api/dashboard/v1/runs/' + id + '/execution', r => r.fulfill({ json: snapshot.execution }));
    await page.route('**/api/dashboard/v1/runs/' + id + '/checks?*', r => r.fulfill({ json: snapshot.checks }));
    await page.goto('/app/runs/' + id);
    await page.getByRole('button', { name: '전체', exact: true }).click();
    const table = page.getByRole('table', { name: '기대값과 관측값' });
    await expect(table.getByRole('row')).toHaveCount(snapshot.checks.checks.length + 1);
    await expect(table.getByRole('cell', { name: 'FAIL', exact: true })).toHaveCount(name === 'registration-invalid-row' ? 2 : 0);
    if (name.startsWith('redis-lock')) {
      await expect(page.getByText('이 페이지에 측정값이 없습니다.', { exact: false })).toBeVisible();
      await expect(page.getByRole('table', { name: '원본 관측 · 한 페이지씩 조회' })).toHaveCount(0);
    } else {
      const metrics = page.getByRole('table', { name: '원본 관측 · 한 페이지씩 조회' });
      await expect(metrics.getByRole('row')).toHaveCount(2);
      for (const key of Object.keys(snapshot.report.metrics)) await expect(metrics).toContainText(key);
    }
    await expect(page.getByRole('link', { name: '실행·검증 포함 내보내기 (NDJSON)' })).toBeVisible();
    await expect(page.getByRole('cell', { name: '참조 전용', exact: true })).toBeVisible();
    await expect(page.getByText(name === 'registration-invalid-row' ? '증거 미완료 · 확인된 부분만 표시' : '증거 수집 완료', { exact: true })).toBeVisible();
    await page.screenshot({ path: info.outputPath(name + '.png'), fullPage: true });
    await page.setViewportSize({ width: 375, height: 812 });
    await expect(table).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
    await page.screenshot({ path: info.outputPath(name + '-mobile.png'), fullPage: true });
  });
}

for (const [scenario, variants] of Object.entries({ deletion: ['BEFORE', 'AFTER'], 'redis-lock': ['BEFORE', 'AFTER'],
  registration: ['BEFORE', 'AFTER', 'INVALID_ROW'] })) {
  test('equity presets submit schema: ' + scenario, async ({ page }) => {
    const id = 'career.equity-system.' + scenario;
    const parameters: Record<string, object> = { variant: { type: 'string', values: variants } };
    if (scenario !== 'redis-lock') parameters.rows = { type: 'integer', minimum: 1, maximum: scenario === 'deletion' ? 50000 : 10000, values: [] };
    if (scenario === 'registration') for (const [key, maximum] of Object.entries({ chunkSize: 1000, workers: 8, maxInFlight: 16 }))
      parameters[key] = { type: 'integer', minimum: 1, maximum, values: [] };
    const descriptor = { id, title: scenario, category: 'CAREER', input: 'NONE', measurementModes: ['SINGLE_PASS'], parameters };
    await page.route('**/api/experiments', r => r.fulfill({ json: [descriptor] }));
    await page.route('**/api/modules', r => r.fulfill({ json: [] }));
    await page.route('**/api/runs/ui/options', r => r.fulfill({ json: { pageSize: 20 } }));
    await page.route('**/api/dashboard/v1/capabilities', r => r.fulfill({ json: { schemaVersion: 1, features: { container: true } } }));
    await page.route('**/api/dashboard/v1/experiments/' + id, r => r.fulfill({ json: { readiness: 'READY', executionKind: 'CONTAINER' } }));
    await page.route('**/api/runs', r => r.fulfill({ status: 202, json: { runId: 'submitted-equity' } }));
    for (const variant of variants) {
      await page.goto('/app/experiments/' + id);
      await page.getByRole('button', { name: scenario + '-' + variant.toLowerCase().replaceAll('_', '-'), exact: true }).click();
      await expect(page.getByLabel(/^variant/)).toHaveValue(variant);
      const request = page.waitForRequest(r => r.method() === 'POST' && r.url().endsWith('/api/runs'));
      await page.getByRole('button', { name: '실험 실행', exact: true }).click();
      expect((await request).postDataJSON()).toMatchObject({ experiment: id, parameters: { variant }, measurement: { mode: 'SINGLE_PASS' } });
      await expect(page.getByRole('link', { name: '접수된 실행 상세 보기' })).toBeVisible();
    }
  });
}
