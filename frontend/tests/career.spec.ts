import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
const evidence = JSON.parse(
  readFileSync(
    new URL('../../contracts/dashboard/v1/fixtures/evidence.json', import.meta.url),
    'utf8',
  ),
);
test.use({ actionTimeout: 15000 });
const experiment = 'career.mcp-platform.sequence';
const path = '/app/experiments/' + experiment;

async function fixtureCatalog(page: Page, kind = 'LOCAL_JVM') {
  await page.route('**/api/runs/ui/options', route => route.fulfill({ json: { pageSize: 20, artifactDownloadMaxBytes: 1048576 } }));
  const descriptor = {
    id: experiment,
    title: 'Contract sequence allocation',
    category: 'CAREER',
    input: 'NONE',
    measurementModes: ['SINGLE_PASS'],
    parameters: {
      variant: {
        type: 'string',
        values: ['BEFORE', 'ATOMIC_UPDATE', 'SELECT_FOR_UPDATE'],
      },
      workers: { type: 'integer', minimum: 2, maximum: 16, values: [] },
      blockSize: { type: 'integer', minimum: 1, maximum: 10000, values: [] },
    },
  };
  await page.route('**/api/experiments', (route) => route.fulfill({ json: [descriptor] }));
  await page.route('**/api/modules', (route) => route.fulfill({ json: [] }));
  await page.route('**/api/dashboard/v1/capabilities', (route) =>
    route.fulfill({
      json: {
        schemaVersion: 1,
        features: {
          localJvm: kind === 'LOCAL_JVM',
          container: kind === 'CONTAINER',
          executionEvidence: true,
          checksRead: true,
          extendedExport: true,
        },
      },
    }),
  );
}

for (const kind of ['LOCAL_JVM', 'CONTAINER']) {
test(kind + ' readiness blocks execution and retry preserves draft', async ({ page }) => {
  await fixtureCatalog(page, kind);
  let state = 'DOCKER_UNAVAILABLE';
  await page.route('**/api/dashboard/v1/experiments/' + experiment, (route) =>
    route.fulfill({ json: { readiness: state, executionKind: kind } }),
  );
  await page.goto(path);
  const submit = page.getByRole('button', { name: '실험 실행', exact: true });
  await expect(page.getByText('Docker 준비 필요', { exact: true })).toBeVisible();
  await expect(submit).toBeDisabled();
  state = 'READY';
  await page.getByRole('button', { name: '준비 상태 다시 확인' }).click();
  await expect(submit).toBeEnabled();
  await page.getByLabel('시나리오 ID').fill('keep-my-draft');
  await page.getByRole('button', { name: '준비 상태 다시 확인' }).click();
  await expect(submit).toBeEnabled();
  await expect(page.getByLabel('시나리오 ID')).toHaveValue('keep-my-draft');
});

}

for (const kind of ['LOCAL_JVM', 'CONTAINER']) {
test(kind + ' partial evidence, unknown worker, check cursor and request failure', async ({ page }) => {
  await fixtureCatalog(page, kind);
  const id = evidence.run.runId;
  let status = 'FAILED';
  let checkRequests = 0;
  let executionDelay = 0;
  await page.route('**/api/runs/' + id, (route) =>
    route.fulfill({
      json: { ...evidence.run, experimentType: experiment, status },
    }),
  );
  await page.route('**/api/runs/' + id + '/artifacts', (route) => route.fulfill({ json: [] }));
  await page.route('**/api/runs/' + id + '/metrics?*', (route) =>
    route.fulfill({ json: { metrics: [], nextAfter: null } }),
  );
  await page.route('**/api/dashboard/v1/runs/' + id + '/execution', async (route) => {
    if (executionDelay) await new Promise((resolve) => setTimeout(resolve, executionDelay));
    return route.fulfill({
      json: {
        available: true,
        execution: {
          definition: {
            kind,
            moduleId: 'mcp-platform',
            measurementScope: 'WORKER_PROCESS',
          },
          phase: 'MEASURING',
          evidenceComplete: false,
          worker: null,
        },
      },
    });
  });
  let fail = false;
  await page.route('**/api/dashboard/v1/runs/' + id + '/checks?*', (route) => {
    checkRequests++;
    if (fail) return route.fulfill({ status: 503, json: {} });
    const after = new URL(route.request().url()).searchParams.get('after');
    return route.fulfill({
      json: {
        available: true,
        checks:
          after === '-1'
            ? [
                {
                  sequence: 8,
                  checkId: 'overlap',
                  status: 'FAIL',
                  expected: '0',
                  observed: '3',
                  description: 'Partial fixture',
                },
              ]
            : [],
        nextAfter: after === '-1' ? 8 : null,
      },
    });
  });
  await page.goto('/app/runs/' + id);
  await expect(page.getByText('증거 미완료 · 확인된 부분만 표시')).toBeVisible();
  await expect(
    page.getByText('Worker 환경은 아직 기록되지 않았습니다.', { exact: false }),
  ).toBeVisible();
  await expect(page.getByRole('cell', { name: 'FAIL', exact: true })).toBeVisible();
  const nextPage = page.waitForRequest((request) => request.url().includes('/checks?after=8&'));
  await page.getByRole('button', { name: '다음 검증' }).click();
  await nextPage;
  await expect(page.getByRole('cell', { name: 'FAIL', exact: true })).toHaveCount(0);
  await expect(page.getByText('이 페이지에 검증 결과가 없습니다.', { exact: false })).toBeVisible();
  fail = true;
  await page.getByRole('button', { name: '첫 검증' }).click();
  await expect(
    page.getByRole('region', { name: '실행 및 검증 증거' }).getByRole('alert'),
  ).toContainText('503');
  fail = false;
  await page.getByRole('button', { name: '증거 다시 조회' }).click();
  await expect(page.getByRole('cell', { name: 'FAIL', exact: true })).toBeVisible();
  status = 'RUNNING';
  executionDelay = 2500;
  checkRequests = 0;
  await page.reload();
  await expect(page.getByText('실행 종료 후 검증 결과를 조회합니다.')).toBeVisible();
  await expect(page.getByText('증거 미완료 · 확인된 부분만 표시')).toBeVisible({ timeout: 8000 });
  expect(checkRequests).toBe(0);
  await page.route('**/api/dashboard/v1/capabilities', (route) => route.fulfill({ status: 404 }));
  await page.reload();
  await expect(page.getByText('기존 API 호환 모드', { exact: false })).toBeVisible();
  await expect(page.getByRole('region', { name: '실행 및 검증 증거' })).toHaveCount(0);
});

}

test('real Career browser execution, comparison and evidence export', async ({
  page,
  request,
}, testInfo) => {
  test.skip(process.env.CAREER_E2E !== '1', 'Requires enabled Career deployment and Docker');
  test.setTimeout(600000);
  const ids: string[] = [];
  for (const variant of ['BEFORE', 'ATOMIC_UPDATE']) {
    await page.goto(path);
    const submit = page.getByRole('button', { name: '실험 실행', exact: true });
    await expect(submit).toBeEnabled({ timeout: 80000 });
    await page
      .getByRole('button', {
        name: variant === 'BEFORE' ? 'sequence-before' : 'sequence-atomic-update',
        exact: true,
      })
      .click();
    await page.getByLabel(/^variant/).selectOption(variant);
    await page.getByLabel('시나리오 ID').fill('browser-' + variant.toLowerCase());
    if (variant === 'BEFORE')
      await page.screenshot({
        path:
          process.env.DOC_SCREENSHOTS === '1'
            ? '../docs/images/16-career-execution.png'
            : testInfo.outputPath('career-execution.png'),
        fullPage: true,
      });
    await submit.click();
    await page.getByRole('link', { name: '접수된 실행 상세 보기' }).click({ timeout: 80000 });
    const id = decodeURIComponent(new URL(page.url()).pathname.split('/').at(-1)!);
    ids.push(id);
    await expect(page.getByText('증거 수집 완료', { exact: true })).toBeVisible({
      timeout: 300000,
    });
    await expect(
      page
        .getByRole('table', { name: '기대값과 관측값' })
        .getByRole('cell', { name: 'PASS', exact: true }),
    ).toHaveCount(2);
    await page.getByText('Worker JVM / 운영체제 / Git', { exact: true }).click();
    await expect(page.getByRole('region', { name: '실행 및 검증 증거' })).toContainText(
      '"javaVersion": "21',
    );
    const exported = await request.get(`/api/dashboard/v1/runs/${id}/export`);
    const records = (await exported.text())
      .trim()
      .split('\n')
      .map((line) => JSON.parse(line));
    expect(records.filter((r) => r.type === 'check')).toHaveLength(2);
    expect(records.at(-1).type).toBe('end');
    await page.reload();
    await expect(page.getByText('증거 수집 완료', { exact: true })).toBeVisible();
    if (variant === 'ATOMIC_UPDATE') {
      await page.getByText('Worker JVM / 운영체제 / Git', { exact: true }).click();
      await page.screenshot({
        path:
          process.env.DOC_SCREENSHOTS === '1'
            ? '../docs/images/17-career-evidence.png'
            : testInfo.outputPath('career-evidence.png'),
        fullPage: true,
      });
      await page.setViewportSize({ width: 375, height: 812 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await page.setViewportSize({ width: 1440, height: 1000 });
    }
  }
  await page.goto('/app/compare?baseline=' + ids[0] + '&candidate=' + ids[1]);
  await expect(
    page
      .getByRole('row', { name: /^sequence\.elapsedNanos \/ counter/ })
      .getByRole('cell', { name: 'ns', exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole('rowheader', {
      name: 'sequence.elapsedNanos / counter',
      exact: true,
    }),
  ).toBeVisible();
});

test('stored Career evidence after server restart', async ({ page }) => {
  test.skip(
    !process.env.CAREER_REOPEN_IDS,
    'Supply two completed Run IDs after restarting the API with the same store',
  );
  const ids = process.env.CAREER_REOPEN_IDS!.split(',');
  expect(ids).toHaveLength(2);
  for (const id of ids) {
    await page.goto('/app/runs/' + encodeURIComponent(id));
    await expect(page.getByText('증거 수집 완료', { exact: true })).toBeVisible();
    await expect(
      page
        .getByRole('table', { name: '기대값과 관측값' })
        .getByRole('cell', { name: 'PASS', exact: true }),
    ).toHaveCount(2);
    await page.getByText('Worker JVM / 운영체제 / Git', { exact: true }).click();
    await expect(page.getByRole('region', { name: '실행 및 검증 증거' })).toContainText(
      '"javaVersion": "21',
    );
    if (process.env.DOC_SCREENSHOTS === '1' && id === ids[1])
      await page.screenshot({ path: '../docs/images/17-career-evidence.png', fullPage: true });
  }
  await page.goto('/app/compare?' + new URLSearchParams({ baseline: ids[0], candidate: ids[1] }));
  await expect(
    page
      .getByRole('row', { name: /^sequence\.elapsedNanos \/ counter/ })
      .getByRole('cell', { name: 'ns', exact: true }),
  ).toBeVisible();
});
