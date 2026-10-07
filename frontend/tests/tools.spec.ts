import { test, expect } from '@playwright/test';
import { fileURLToPath } from 'node:url';
test('Excel both modes, processing detail, current DB and nonpersistent IO tools', async ({
  page,
  request,
}, info) => {
  for (const mode of ['PIPELINE', 'SINGLE_THREAD']) {
    await page.goto('/app/experiments?experiment=pipeline.excel-ingestion');
    await page
      .getByLabel('XLSX 파일')
      .setInputFiles(fileURLToPath(new URL('./fixtures/upload.xlsx', import.meta.url)));
    await page.getByLabel('실행 모드').selectOption(mode);
    await page.getByRole('button', { name: '업로드 실행' }).click();
    const detail = page.getByRole('link', { name: 'Excel 작업 상세 보기' });
    await expect(detail).toBeVisible();
    const id = (await detail.getAttribute('href'))!.split('/').at(-1)!;
    await expect
      .poll(async () => (await (await request.get('/api/runs/' + id)).json()).status)
      .toBe('SUCCEEDED');
    await detail.click();
    await expect(page.getByRole('table', { name: '행 집계' })).toContainText('20');
    await expect(page.getByRole('table', { name: '행 집계' })).toContainText(mode);
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Excel 처리 현황' })).toBeVisible();
  }
  await page.screenshot({
    path:
      process.env.DOC_SCREENSHOTS === '1'
        ? '../docs/images/13-excel-detail.png'
        : info.outputPath('excel.png'),
    fullPage: true,
  });
  await page.goto('/app/tools/ingestion-db');
  await page.getByLabel('최신 행 수').fill('1');
  await page.getByRole('button', { name: 'DB 조회', exact: true }).click();
  await expect(page.getByRole('table', { name: '현재 DB 최신 행' }).getByRole('row')).toHaveCount(
    2,
  );
  const before = await (await request.get('/api/runs?limit=1')).json();
  await page.goto('/app/tools/io-echo?msg=demo');
  await page.getByLabel('지연 (ms)', { exact: true }).fill('0');
  await page.getByRole('button', { name: 'Virtual thread 요청' }).click();
  await expect(
    page.getByRole('cell', { name: '[virtual-thread] demo', exact: false }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Reactive 요청' }).click();
  await expect(page.getByRole('cell', { name: '[reactive] demo', exact: false })).toBeVisible();
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: '데모 CSV' }).click();
  expect((await download).suggestedFilename()).toBe('io-echo.csv');
  const after = await (await request.get('/api/runs?limit=1')).json();
  expect(after.runs[0].runId).toBe(before.runs[0].runId);
  await page.setViewportSize({ width: 375, height: 812 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
});
test('upload rejection and DB failure are visible', async ({ page }) => {
  await page.route('**/api/ingest/excel', (route) => route.fulfill({ status: 413 }));
  await page.goto('/app/experiments?experiment=pipeline.excel-ingestion');
  await page
    .getByLabel('XLSX 파일')
    .setInputFiles(fileURLToPath(new URL('./fixtures/upload.xlsx', import.meta.url)));
  await page.getByRole('button', { name: '업로드 실행' }).click();
  await expect(page.getByRole('alert')).toContainText('413');
  await page.route('**/api/ingest/db/count', (route) => route.fulfill({ status: 503 }));
  await page.goto('/app/tools/ingestion-db');
  await page.getByRole('button', { name: 'DB 조회', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('503');
});
