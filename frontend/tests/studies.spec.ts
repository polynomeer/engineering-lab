import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
const id = 'concurrency.queue-contention';
test('actual study API preserves downloadable definition, case provenance and pattern version', async ({page,request}, info) => {
  const response = await request.get('/api/dashboard/v1/study-catalog');
  expect(response.ok()).toBe(true);
  const body = await response.json(); const study = body.studies.find((s: any) => s.experimentId === id);
  await page.goto('/app/catalog?pattern=bounded-queue');
  const card = page.locator('.pattern-result').filter({has:page.getByRole('heading',{name:'Queue contention',exact:true})});
  await card.getByText('전략·사례·시나리오',{exact:true}).click();
  await expect(card).toContainText('NEW-DESIGN');
  await expect(page.getByText('의미 버전 1.0.0', {exact:false})).toBeVisible();
  await card.getByText('small-buffer',{exact:true}).click();
  const downloaded = page.waitForEvent('download');
  await card.getByRole('button',{name:'정의 JSON 저장'}).click();
  const file = await (await downloaded).path();
  expect(await readFile(file!, 'utf8')).toBe(study.presets[0].definitionJson);
  await page.screenshot({path:info.outputPath('study-desktop.png'),fullPage:true});
  await page.setViewportSize({width:375,height:812});
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({path:info.outputPath('study-mobile.png'),fullPage:true});
});
for (const mode of ['404','500','version','malformed']) test(`study ${mode} does not block discovery`, async ({page,request}) => {
  const experiments = await (await request.get('/api/experiments')).json();
  expect(experiments.map((e: any) => e.id)).toContain(id);
  await page.route('**/api/dashboard/v1/study-catalog',r => mode === 'version' ? r.fulfill({json:{schemaVersion:2}}) :
    mode === 'malformed' ? r.fulfill({json:{schemaVersion:1}}) : r.fulfill({status:Number(mode)}));
  await page.goto('/app/catalog');
  await expect(page.locator('.pattern-result')).toHaveCount(experiments.length);
  if (mode === '500' || mode === 'malformed') {
    await expect(page.getByRole('alert')).toContainText('사례 조회 실패');
    await page.unroute('**/api/dashboard/v1/study-catalog');
    await page.getByRole('button',{name:'사례 다시 시도'}).click();
    await expect(page.getByText('전략·사례·시나리오',{exact:true})).toHaveCount(experiments.length);
  } else await expect(page.getByText('기존 탐색을 계속 사용할 수 있습니다.',{exact:false})).toBeVisible();
  await page.locator('.pattern-result').filter({has:page.getByRole('heading',{name:'Queue contention',exact:true})}).getByRole('link',{name:'실험 설정',exact:true}).click();
  await expect(page.getByRole('combobox',{name:'실험 선택',exact:true})).toHaveValue(id);
});

test('deprecated pattern retains its URL and shows an advisory replacement', async ({page,request}) => {
  const catalog = await (await request.get('/api/dashboard/v1/pattern-catalog')).json();
  const count = catalog.experiments.filter((e: any) => e.classification?.patternIds.includes('bounded-queue')).length;
  expect(count).toBeGreaterThan(0);
  await page.route('**/api/dashboard/v1/study-catalog', async r => {
    const response = await r.fetch(); const body = await response.json();
    body.patterns = body.patterns.map((p: any) => p.patternId === 'bounded-queue'
      ? {...p,status:'DEPRECATED',semanticVersion:'1.1.0',replacementPatternId:'bounded-parallelism'} : p);
    await r.fulfill({json:body});
  });
  await page.goto('/app/catalog?pattern=bounded-queue');
  await expect(page.getByText('의미 버전 1.1.0', {exact:false})).toContainText('자동 치환하지 않음');
  await expect(page).toHaveURL(/pattern=bounded-queue$/);
  await expect(page.locator('.pattern-result')).toHaveCount(count);
});
