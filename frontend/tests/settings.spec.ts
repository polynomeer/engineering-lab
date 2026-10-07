import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
const key = 'engineering-lab.experiments.dashboard';
const legacy = {
  queue: {
    scenarioId: 'migrated-queue',
    queueCapacity: '4',
    producerThreads: '2',
    consumerThreads: '1',
    itemsPerProducer: '40',
    consumerDelayMs: '1',
  },
  browser: { compareA: 'run-a', compareB: 'run-b', jobFilter: 'concurrency.queue-contention' },
};
test('different origins: legacy export, preview, selected import, restore and original retained', async ({
  page,
}, info) => {
  await page.goto(process.env.LEGACY_URL ?? 'http://127.0.0.1:18080/experiments/ui');
  await page.locator('#scenarioId').fill('migrated-queue');
  await page.evaluate(() => {
    sessionStorage.setItem('lab.compare.baseline', 'run-a');
    sessionStorage.setItem('lab.compare.candidate', 'run-b');
  });
  const event = page.waitForEvent('download');
  await page.getByRole('button', { name: '설정 내보내기', exact: true }).click();
  const file = await event;
  const bytes = await readFile((await file.path())!);
  expect(bytes.toString()).toContain('migrated-queue');
  await page.goto('/app/settings');
  await expect(page.getByLabel('설정 JSON')).toBeEnabled();
  await page
    .getByLabel('설정 JSON')
    .setInputFiles({ name: 'settings.json', mimeType: 'application/json', buffer: bytes });
  await expect(page.getByText('미리보기만 완료했습니다.', { exact: false })).toBeVisible();
  expect(
    await page.evaluate(() => localStorage.getItem('engineering-lab.dashboard.presets.v1')),
  ).toBeNull();
  await page.screenshot({
    path:
      process.env.DOC_SCREENSHOTS === '1'
        ? '../docs/images/14-settings-import.png'
        : info.outputPath('settings.png'),
    fullPage: true,
  });
  await page.setViewportSize({ width: 375, height: 812 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
  await page.getByLabel('io.endpoint-comparison', { exact: false }).uncheck();
  await page.getByRole('button', { name: '선택한 설정 가져오기' }).click();
  await expect(page.getByRole('status').filter({ hasText: '저장했습니다' })).toBeVisible();
  await page.goto('/app/experiments?experiment=concurrency.queue-contention');
  await page.getByRole('button', { name: 'migrated-queue 복원' }).click();
  await expect(page.getByLabel('시나리오 ID', { exact: false })).toHaveValue('migrated-queue');
  await page.goto('/app/compare');
  await expect(page.getByLabel('기준 Run ID')).toHaveValue('run-a');
  await page.goto(process.env.LEGACY_URL ?? 'http://127.0.0.1:18080/experiments/ui');
  expect(
    await page.evaluate((key) => JSON.parse(localStorage.getItem(key)!).queue.scenarioId, key),
  ).toBe('migrated-queue');
});
test('same origin import, malformed file, conflict policy, IO persistence and blocked storage', async ({
  page,
}) => {
  await page.goto('/app/settings');
  await expect(page.getByLabel('설정 JSON')).toBeEnabled();
  await page.evaluate(({ key, legacy }) => localStorage.setItem(key, JSON.stringify(legacy)), {
    key,
    legacy,
  });
  await page.getByRole('button', { name: '같은 주소의 기존 설정 읽기' }).click();
  await page.getByRole('button', { name: '선택한 설정 가져오기' }).click();
  await page.evaluate(
    ({ key, legacy }) =>
      localStorage.setItem(
        key,
        JSON.stringify({ ...legacy, queue: { ...legacy.queue, queueCapacity: '8' } }),
      ),
    { key, legacy },
  );
  await page.getByRole('button', { name: '같은 주소의 기존 설정 읽기' }).click();
  await page.getByRole('button', { name: '선택한 설정 가져오기' }).click();
  expect(
    await page.evaluate(
      () =>
        JSON.parse(localStorage.getItem('engineering-lab.dashboard.presets.v1')!).entries[0].draft
          .parameters.queueCapacity,
    ),
  ).toBe(4);
  await page.getByRole('button', { name: '같은 주소의 기존 설정 읽기' }).click();
  await page.getByLabel('같은 실험·시나리오 ID의 충돌 처리').selectOption('true');
  await page.getByRole('button', { name: '선택한 설정 가져오기' }).click();
  expect(
    await page.evaluate(
      () =>
        JSON.parse(localStorage.getItem('engineering-lab.dashboard.presets.v1')!).entries[0].draft
          .parameters.queueCapacity,
    ),
  ).toBe(8);
  await page.getByLabel('설정 JSON').setInputFiles({
    name: 'bad.json',
    mimeType: 'application/json',
    buffer: Buffer.from('{"version":9}'),
  });
  await expect(page.getByRole('alert')).toContainText('버전');
  await page.goto('/app/tools/io-echo');
  await page.getByRole('button', { name: 'Reactive 요청' }).click();
  await expect(page.getByRole('cell', { name: '[reactive] hello', exact: false })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('cell', { name: '[reactive] hello', exact: false })).toBeVisible();
  await page.addInitScript(() => {
    Storage.prototype.setItem = () => {
      throw new Error('quota');
    };
  });
  await page.goto('/app/settings');
  await expect(page.getByLabel('설정 JSON')).toBeEnabled();
  await page.getByRole('button', { name: '같은 주소의 기존 설정 읽기' }).click();
  await page.getByRole('button', { name: '선택한 설정 가져오기' }).click();
  await expect(page.getByRole('alert')).toContainText('quota');
});
