import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';

// Live API only: use the index produced by accept-local-deployment.py.
test('all deployed modules retain checks and downloadable reports', async ({ page }, info) => {
  test.skip(!process.env.DEPLOYMENT_ACCEPTANCE, 'Requires a completed local deployment acceptance');
  test.setTimeout(180000);
  const directory = resolve(process.env.DEPLOYMENT_ACCEPTANCE!);
  const index = JSON.parse(readFileSync(resolve(directory, 'index.json'), 'utf8'));
  expect(index.complete).toBe(true);
  expect(index.runs).toHaveLength(17);
  for (const entry of index.runs) {
    const snapshot = JSON.parse(readFileSync(resolve(directory, entry.name + '.json'), 'utf8'));
    await page.goto('/app/runs/' + encodeURIComponent(entry.runId));
    await expect(page.getByText(entry.expectedStatus === 'SUCCEEDED'
      ? '증거 수집 완료' : '증거 미완료 · 확인된 부분만 표시', { exact: true })).toBeVisible();
    const table = page.getByRole('table', { name: '기대값과 관측값' });
    await expect(table.getByRole('row')).toHaveCount(snapshot.checks.checks.length + 1);
    for (const check of snapshot.checks.checks) {
      const row = table.getByRole('row').filter({ hasText: check.checkId });
      await expect(row.getByRole('cell', { name: check.status, exact: true })).toBeVisible();
      await expect(row).toContainText(check.observed);
    }
    const links = page.getByRole('link', { name: 'REPORT 다운로드 ↗' });
    await expect(links).toHaveCount(snapshot.artifacts.length);
    for (const [i, artifact] of snapshot.artifacts.entries()) {
      const pending = page.waitForEvent('download');
      await links.nth(i).click();
      const download = await pending;
      expect(await download.failure()).toBeNull();
      const stream = await download.createReadStream();
      const hash = createHash('sha256');
      let size = 0;
      for await (const chunk of stream!) {
        size += chunk.length;
        expect(size).toBeLessThanOrEqual(16 * 1024 * 1024);
        hash.update(chunk);
      }
      expect(size).toBe(artifact.byteSize);
      expect(hash.digest('hex')).toBe(artifact.sha256);
    }
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: info.outputPath(entry.name + '.png'), fullPage: true });
    await page.setViewportSize({ width: 375, height: 812 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
    await page.setViewportSize({ width: 1440, height: 1000 });
  }
});
