import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// Recorded actual container/API results; opt in after creatorContainerTest.
for (const name of ['session-rotation-before', 'session-rotation-after', 'session-rotation-concurrent',
  'mapping-integrity-before', 'mapping-integrity-after', 'mapping-integrity-transfer',
  'post-commit-commit', 'post-commit-rollback', 'post-commit-retry', 'post-commit-exhausted']) {
  test('actual Creator snapshot: ' + name, async ({ page }, info) => {
    test.skip(!process.env.CREATOR_EVIDENCE, 'Run creatorContainerTest and set CREATOR_EVIDENCE');
    const snapshot = JSON.parse(readFileSync(resolve(process.env.CREATOR_EVIDENCE!, name + '.json'), 'utf8'));
    const id = snapshot.run.runId;
    await page.route('**/api/experiments', r => r.fulfill({ json: [] }));
    await page.route('**/api/modules', r => r.fulfill({ json: [] }));
    await page.route('**/api/runs/ui/options', r => r.fulfill({ json: { pageSize: 20, artifactDownloadMaxBytes: 1048576 } }));
    await page.route('**/api/dashboard/v1/capabilities', r => r.fulfill({ json: { schemaVersion: 1,
      features: { container: true, executionEvidence: true, checksRead: true, extendedExport: true } } }));
    await page.route('**/api/runs/' + id, r => r.fulfill({ json: snapshot.run }));
    await page.route('**/api/runs/' + id + '/metrics?*', r => r.fulfill({ json: { metrics: [], nextAfter: null } }));
    await page.route('**/api/runs/' + id + '/artifacts', r => r.fulfill({ json: snapshot.artifacts }));
    await page.route('**/api/dashboard/v1/runs/' + id + '/execution', r => r.fulfill({ json: snapshot.execution }));
    await page.route('**/api/dashboard/v1/runs/' + id + '/checks?*', r => r.fulfill({ json: snapshot.checks }));
    await page.goto('/app/runs/' + id);
    await page.getByRole('button', { name: '전체', exact: true }).click();
    const table = page.getByRole('table', { name: '기대값과 관측값' });
    await expect(table.getByRole('row')).toHaveCount(snapshot.checks.checks.length + 1);
    await expect(table.getByRole('cell', { name: 'FAIL', exact: true })).toHaveCount(name === 'post-commit-exhausted' ? 1 : 0);
    await expect(page.getByText('이 페이지에 측정값이 없습니다.', { exact: false })).toBeVisible();
    await expect(page.getByRole('table', { name: '원본 관측 · 한 페이지씩 조회' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: '실행·검증 포함 내보내기 (NDJSON)' })).toBeVisible();
    await expect(page.getByRole('cell', { name: '참조 전용', exact: true })).toBeVisible();
    await expect(page.getByText(name === 'post-commit-exhausted' ? '증거 미완료 · 확인된 부분만 표시' : '증거 수집 완료', { exact: true })).toBeVisible();
    for (const check of snapshot.report.checks)
      await expect(table.getByRole('row').filter({ hasText: check.checkId })).toContainText(check.observed);
    await page.screenshot({ path: info.outputPath(name + '.png'), fullPage: true });
    await page.setViewportSize({ width: 375, height: 812 });
    await expect(table).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
    await page.screenshot({ path: info.outputPath(name + '-mobile.png'), fullPage: true });
  });
}

for (const [scenario, variants] of Object.entries({ 'session-rotation': ['BEFORE', 'AFTER', 'CONCURRENT'],
  'mapping-integrity': ['BEFORE', 'AFTER', 'TRANSFER'], 'post-commit': ['COMMIT', 'ROLLBACK', 'RETRY', 'EXHAUSTED'] })) {
  test('Creator presets submit the schema: ' + scenario, async ({ page }) => {
    const id = 'career.creator-studio.' + scenario;
    const descriptor = { id, title: scenario, category: 'CAREER', input: 'NONE', measurementModes: ['SINGLE_PASS'],
      parameters: { variant: { type: 'string', values: variants } } };
    await page.route('**/api/experiments', r => r.fulfill({ json: [descriptor] }));
    await page.route('**/api/modules', r => r.fulfill({ json: [] }));
    await page.route('**/api/runs/ui/options', r => r.fulfill({ json: { pageSize: 20 } }));
    await page.route('**/api/dashboard/v1/capabilities', r => r.fulfill({ json: { schemaVersion: 1, features: { container: true } } }));
    await page.route('**/api/dashboard/v1/experiments/' + id, r => r.fulfill({ json: { readiness: 'READY', executionKind: 'CONTAINER' } }));
    await page.route('**/api/runs', r => r.fulfill({ status: 202, json: { runId: 'submitted-checks' } }));
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
