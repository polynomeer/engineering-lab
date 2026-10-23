import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
const evidence = JSON.parse(readFileSync('../contracts/dashboard/v1/fixtures/evidence.json', 'utf8'));

test('a run lists the Finding documents that cite it and links to their Markdown', async ({ page }) => {
  const id = 'cited-run';
  await page.route('**/api/experiments', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/modules', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/runs/ui/options', (r) => r.fulfill({ json: { pageSize: 2 } }));
  await page.route('**/api/dashboard/v1/capabilities', (r) => r.fulfill({ json: { schemaVersion: 1, features: {} } }));
  await page.route('**/api/runs/' + id, (r) => r.fulfill({ json: { ...evidence.run, runId: id } }));
  await page.route('**/api/runs/' + id + '/artifacts', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/runs/' + id + '/metrics?*', (r) => r.fulfill({ json: { metrics: [], nextAfter: null } }));
  await page.route('**/api/runs/' + id + '/findings', (r) =>
    r.fulfill({ json: [{ file: '2026-10-04-pipeline.md', title: 'Finding: 저장 대기 병렬화' }] }),
  );
  await page.goto('/app/runs/' + id);
  const panel = page.getByRole('region', { name: '이 Run을 인용한 Finding' });
  await expect(panel.getByRole('link', { name: 'Finding: 저장 대기 병렬화' })).toHaveAttribute(
    'href',
    '/api/findings/2026-10-04-pipeline.md',
  );
});

test('the panel stays hidden when no Finding cites the run or the server has no index', async ({ page }) => {
  const id = 'uncited-run';
  await page.route('**/api/experiments', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/modules', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/runs/ui/options', (r) => r.fulfill({ json: { pageSize: 2 } }));
  await page.route('**/api/dashboard/v1/capabilities', (r) => r.fulfill({ json: { schemaVersion: 1, features: {} } }));
  await page.route('**/api/runs/' + id, (r) => r.fulfill({ json: { ...evidence.run, runId: id } }));
  await page.route('**/api/runs/' + id + '/artifacts', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/runs/' + id + '/metrics?*', (r) => r.fulfill({ json: { metrics: [], nextAfter: null } }));
  await page.route('**/api/runs/' + id + '/findings', (r) => r.fulfill({ status: 404 }));
  await page.goto('/app/runs/' + id);
  await expect(page.getByRole('heading', { name: '실행 기록과 검증 증거' })).toBeVisible();
  await expect(page.getByRole('region', { name: '이 Run을 인용한 Finding' })).toHaveCount(0);
});
