import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
const evidence = JSON.parse(
  readFileSync('../contracts/dashboard/v1/fixtures/evidence.json', 'utf8'),
);

async function pipelineRun(page: Page, id: string, status = 'SUCCEEDED') {
  await page.route('**/api/experiments', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/modules', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/runs/ui/options', (r) => r.fulfill({ json: { pageSize: 2 } }));
  await page.route('**/api/dashboard/v1/capabilities', (r) =>
    r.fulfill({ json: { schemaVersion: 1, features: {} } }),
  );
  await page.route('**/api/runs?*', (r) => r.fulfill({ json: { runs: [], nextBefore: null } }));
  await page.route('**/api/runs/' + id, (r) =>
    r.fulfill({
      json: {
        ...evidence.run,
        runId: id,
        status,
        experimentType: 'pipeline.excel-ingestion',
        scenarioId: 'Pipeline · dead-letter',
        summary: { counters: { 'rows.validationErrors': 3 }, gauges: {}, details: {} },
      },
    }),
  );
  await page.route('**/api/runs/' + id + '/artifacts', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/runs/' + id + '/metrics?*', (r) =>
    r.fulfill({ json: { metrics: [], nextAfter: null } }),
  );
}

test('dead-letter tab pages validation failures with row context and page-scoped reasons', async ({
  page,
}) => {
  const id = 'dead-letter-run';
  await pipelineRun(page, id);
  const requested: string[] = [];
  await page.route('**/api/runs/' + id + '/dead-letters?*', (r) => {
    const after = new URL(r.request().url()).searchParams.get('after');
    requested.push(after ?? '');
    r.fulfill({
      json:
        after === '-1'
          ? {
              runId: id,
              rows: [
                { rowIndex: 4, stage: 'validation', reason: 'col3 is not an integer', cells: ['a4', 'b4', 'x'] },
                { rowIndex: 9, stage: 'validation', reason: 'col3 is not an integer', cells: ['a9', 'b9', ''] },
              ],
              nextAfter: 9,
            }
          : {
              runId: id,
              rows: [{ rowIndex: 12, stage: 'validation', reason: 'col1 is blank', cells: [] }],
              nextAfter: null,
            },
    });
  });
  await page.goto('/app/runs/' + id);
  await page.getByRole('button', { name: 'Dead-letter', exact: true }).click();
  const table = page.getByRole('table', { name: 'Dead-letter 행 · 현재 페이지' });
  await expect(table.getByRole('row')).toHaveCount(3);
  await expect(table).toContainText('"a4", "b4", "x"');
  await expect(page.getByRole('list', { name: '현재 페이지 사유별 건수' })).toContainText(
    'col3 is not an integer 2',
  );
  await page.getByRole('button', { name: '다음 dead-letter', exact: true }).click();
  await expect(table).toContainText('빈 행');
  await expect(page.getByRole('button', { name: '다음 dead-letter', exact: true })).toHaveCount(0);
  expect(requested).toEqual(['-1', '9']);
});

test('dead-letter tab waits for a terminal run and reports an empty result', async ({ page }) => {
  await pipelineRun(page, 'running-run', 'RUNNING');
  await page.goto('/app/runs/running-run');
  await page.getByRole('button', { name: 'Dead-letter', exact: true }).click();
  await expect(page.getByText('실행 종료 후 dead-letter를 조회합니다.')).toBeVisible();

  await page.unrouteAll({ behavior: 'ignoreErrors' });
  await pipelineRun(page, 'clean-run');
  await page.route('**/api/runs/clean-run/dead-letters?*', (r) =>
    r.fulfill({ json: { runId: 'clean-run', rows: [], nextAfter: null } }),
  );
  await page.goto('/app/runs/clean-run');
  await page.getByRole('button', { name: 'Dead-letter', exact: true }).click();
  await expect(page.getByText('이 실행에는 dead-letter가 없습니다.')).toBeVisible();
});
