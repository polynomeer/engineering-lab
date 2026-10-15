import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

for (const supported of [true, false]) {
  test(`managed REPORT download support=${supported}`, async ({ page }, info) => {
    const snapshot = JSON.parse(readFileSync(resolve('tests/fixtures/creator-report.json'), 'utf8'));
    const id = snapshot.run.runId;
    await page.route('**/api/experiments', r => r.fulfill({ json: [] }));
    await page.route('**/api/modules', r => r.fulfill({ json: [] }));
    await page.route('**/api/runs/ui/options', r => r.fulfill({ json: { pageSize: 20, artifactDownloadMaxBytes: 1048576 } }));
    await page.route('**/api/dashboard/v1/capabilities', r => r.fulfill({ json: { schemaVersion: 1,
      features: { workerReportDownload: supported, executionEvidence: true, checksRead: true } } }));
    await page.route('**/api/runs/' + id, r => r.fulfill({ json: snapshot.run }));
    await page.route('**/api/runs/' + id + '/metrics?*', r => r.fulfill({ json: { metrics: [], nextAfter: null } }));
    await page.route('**/api/runs/' + id + '/artifacts', r => r.fulfill({ json: snapshot.artifacts }));
    await page.route('**/api/dashboard/v1/runs/' + id + '/execution', r => r.fulfill({ json: snapshot.execution }));
    await page.route('**/api/dashboard/v1/runs/' + id + '/checks?*', r => r.fulfill({ json: snapshot.checks }));
    await page.goto('/app/runs/' + id);
    const link = page.getByRole('link', { name: 'REPORT 다운로드 ↗' });
    if (supported) {
      await expect(link).toHaveAttribute('href', `/api/runs/${id}/artifacts/${snapshot.artifacts[0].artifactId}/download`);
      await expect(link).toHaveAttribute('target', '_blank');
      await page.screenshot({ path: info.outputPath('report-desktop.png'), fullPage: true });
      await page.setViewportSize({ width: 375, height: 812 });
      await expect(link).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
      await page.screenshot({ path: info.outputPath('report-mobile.png'), fullPage: true });
    } else {
      await expect(link).toHaveCount(0);
      await expect(page.getByRole('cell', { name: '참조 전용', exact: true })).toBeVisible();
    }
  });
}
