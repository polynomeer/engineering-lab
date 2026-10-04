import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
test('schema forms execute queue and IO; presets survive reload; CSV downloads', async ({
  page,
  request,
}, testInfo) => {
  const runs: string[] = [];
  for (const [experiment, preset] of [
    ['concurrency.queue-contention', 'queue-quick-demo'],
    ['io.endpoint-comparison', 'io-fast-echo'],
  ]) {
    await page.goto('/app/experiments?experiment=' + experiment);
    await page.getByRole('button', { name: preset, exact: true }).click();
    const id = 'form-' + experiment + '-' + Date.now();
    await page.getByLabel('시나리오 ID', { exact: false }).fill(id);
    await page.getByRole('button', { name: '설정 저장', exact: true }).click();
    await expect(
      page.getByText('이 브라우저에 설정을 저장했습니다.', { exact: false }),
    ).toBeVisible();
    await page.reload();
    await page.getByRole('button', { name: id + ' 복원' }).click();
    await expect(page.getByLabel('시나리오 ID', { exact: false })).toHaveValue(id);
    const posted = page.waitForResponse(
      (r) => r.url().endsWith('/api/runs') && r.request().method() === 'POST',
    );
    await page.getByRole('button', { name: '실험 실행', exact: true }).click();
    const response = await posted;
    expect(response.status()).toBe(202);
    const { runId } = await response.json();
    runs.push(runId);
    await page.getByRole('link', { name: '접수된 실행 상세 보기' }).click();
    await expect
      .poll(async () => (await (await request.get('/api/runs/' + runId)).json()).status)
      .toBe('SUCCEEDED');
    await expect(page.getByRole('heading', { name: id, exact: true })).toBeVisible();
  }
  await page.goto('/app/compare?baseline=' + runs[0] + '&candidate=' + runs[1]);
  const downloaded = page.waitForEvent('download');
  await page.getByRole('button', { name: '비교 요약 CSV' }).click();
  const file = await downloaded;
  expect(file.suggestedFilename()).toBe('run-comparison.csv');
  expect(await readFile((await file.path())!, 'utf8')).toContain(runs[0]);
  await page.goto('/app/experiments?experiment=concurrency.queue-contention');
  await expect(page.getByLabel('queueCapacity', { exact: false })).toBeVisible();
  await page.screenshot({
    path:
      process.env.DOC_SCREENSHOTS === '1'
        ? '../docs/images/12-standalone-execution.png'
        : testInfo.outputPath('execution.png'),
    fullPage: true,
  });
  await page.setViewportSize({ width: 375, height: 812 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
  await page.screenshot({ path: testInfo.outputPath('execution-mobile.png'), fullPage: true });
});
test('storage unavailable and uncertain submission leave a usable form without retry', async ({
  page,
}) => {
  await page.addInitScript(() => {
    Storage.prototype.getItem = () => {
      throw new Error('denied');
    };
    Storage.prototype.setItem = () => {
      throw new Error('denied');
    };
  });
  let posts = 0;
  await page.route('**/api/runs', async (route) => {
    if (route.request().method() === 'POST') {
      posts++;
      await route.abort();
    } else await route.continue();
  });
  await page.goto('/app/experiments?experiment=io.endpoint-comparison');
  await expect(
    page.getByText('저장 없이 실험을 실행할 수 있습니다.', { exact: false }),
  ).toBeVisible();
  await page.getByRole('button', { name: '실험 실행', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('접수 여부를 확인할 수 없습니다');
  await expect(page.getByRole('alert')).toBeFocused();
  expect(posts).toBe(1);
});
