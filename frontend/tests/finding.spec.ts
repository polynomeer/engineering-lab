import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
const evidence = JSON.parse(readFileSync('../contracts/dashboard/v1/fixtures/evidence.json', 'utf8'));

test('a run comparison downloads a Finding draft with recorded facts and no conclusion', async ({ page }) => {
  const run = (id: string) => ({ ...evidence.run, runId: id });
  await page.route('**/api/runs/compare?*', (r) =>
    r.fulfill({
      json: {
        baseline: run('aaaaaaaa-base'),
        candidate: run('bbbbbbbb-cand'),
        cautions: [],
        conditions: [{ field: 'parameters.queueCapacity', baseline: '2', candidate: '8', different: true }],
        values: [{ kind: 'counter', name: 'items.consumed', unit: 'items', baseline: 10, candidate: 12 }],
      },
    }),
  );
  await page.route('**/api/dashboard/v1/comparison-assessment?*', (r) => r.fulfill({ status: 404 }));
  await page.goto('/app/compare?baseline=aaaaaaaa-base&candidate=bbbbbbbb-cand');
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Finding 초안 (.md)' }).click();
  const file = await download;
  expect(file.suggestedFilename()).toBe('finding-draft-aaaaaaaa-bbbbbbbb.md');
  const text = await readFile((await file.path())!, 'utf8');
  expect(text).toContain('# Finding: <작성 필요>');
  expect(text).toContain('`parameters.queueCapacity`: "2" → "8"');
  expect(text).toContain('| items.consumed (counter) / items | 10 | 12 | 각 1회 / 변동 미측정 | Run summary |');
  expect(text).toContain('## Interpretation\n\n<작성 필요>');
});
