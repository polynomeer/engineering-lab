import { test, expect } from '@playwright/test';
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
const exported = { ...legacy, io: { scenarioId: 'migrated-io', requests: '20', delayMs: '10', pinning: '0', pinDelayMs: '5' } };
test('a file exported from the removed screens: preview, selected import and restore', async ({
  page,
}, info) => {
  // The format the old screens' 'settings export' button wrote (engineering-lab-legacy v1).
  const bytes = Buffer.from(
    JSON.stringify({
      format: 'engineering-lab-legacy',
      version: 1,
      local: { [key]: JSON.stringify(exported), 'engineering-lab.experiments.preset-history': null, 'engineering-lab.io-history': null },
      session: { baseline: 'run-a', candidate: 'run-b' },
    }),
  );
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
