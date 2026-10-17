import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
// Real P1 definitions, all 16 bindings; synthetic descriptors avoid invoking workers.
const metadata = JSON.parse(readFileSync('tests/fixtures/pattern-catalog.json', 'utf8'));
const descriptors = metadata.experiments.map((e: any) => ({ id:e.experimentId,title:e.experimentId,
  category:'TEST',input:'NONE',measurementModes:['SINGLE_PASS'],parameters:{} }));
const modules = [...new Set(metadata.experiments.map((e: any) => e.sourceModuleId))].map(id => ({
  module:{id,title:id,collection:metadata.experiments.find((e: any) => e.sourceModuleId === id).sourceCollection},
  experiments:descriptors.filter((d: any) => metadata.experiments.find((e: any) => e.experimentId === d.id).sourceModuleId === id),
}));
async function mock(page: Page) {
  await page.route('**/api/experiments', r => r.fulfill({json:descriptors}));
  await page.route('**/api/modules', r => r.fulfill({json:modules}));
  await page.route('**/api/dashboard/v1/capabilities', r => r.fulfill({json:{schemaVersion:1,features:{}}}));
  await page.route('**/api/runs/ui/options', r => r.fulfill({json:{pageSize:20,artifactDownloadMaxBytes:0}}));
  await page.route('**/api/runs?*', r => r.fulfill({json:{runs:[],nextBefore:null}}));
  await page.route('**/api/dashboard/v1/pattern-catalog', r => r.fulfill({json:metadata}));
}
test('all 16 experiments remain reachable; pattern/source filters persist across reload and back', async ({page}, info) => {
  await mock(page);
  await page.goto('/app/catalog');
  await expect(page.locator('.pattern-result')).toHaveCount(16);
  for (const d of descriptors) {
    const card = page.locator('.pattern-result').filter({has:page.getByRole('heading',{name:d.id,exact:true})});
    await card.getByRole('link',{name:'실험 설정',exact:true}).click();
    await expect(page.getByRole('combobox',{name:'실험 선택',exact:true})).toHaveValue(d.id);
    await page.goBack();
    await expect(page.locator('.pattern-result')).toHaveCount(16);
  }
  await page.getByRole('combobox',{name:'패턴',exact:true}).selectOption('bounded-queue');
  await page.getByRole('button',{name:'필터 적용'}).click();
  await expect(page.locator('.pattern-result')).toHaveCount(3);
  await expect(page.getByRole('region',{name:'선택한 패턴 설명'})).toContainText('전체 프로세스 메모리');
  await page.getByRole('combobox',{name:'프로젝트 출처',exact:true}).selectOption('equity-system');
  await page.getByRole('button',{name:'필터 적용'}).click();
  await expect(page.locator('.pattern-result')).toHaveCount(1);
  await page.reload();
  await expect(page.getByRole('combobox',{name:'패턴',exact:true})).toHaveValue('bounded-queue');
  await expect(page.getByRole('combobox',{name:'프로젝트 출처',exact:true})).toHaveValue('equity-system');
  await page.locator('.pattern-result').getByRole('link',{name:'실행 기록',exact:true}).click();
  await expect(page).toHaveURL(/experiment=career.equity-system.registration/);
  await page.goBack();
  await expect(page.locator('.pattern-result')).toHaveCount(1);
  await page.goto('/app/catalog?category=data-processing');
  await expect(page.locator('.pattern-result')).toHaveCount(4);
  await page.screenshot({path:info.outputPath('pattern-desktop.png'),fullPage:true});
  for (const size of [{width:375,height:812},{width:812,height:375},{width:768,height:1024}]) {
    await page.setViewportSize(size);
    await page.emulateMedia({reducedMotion:'reduce'});
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  await page.setViewportSize({width:375,height:812});
  await page.screenshot({path:info.outputPath('pattern-mobile.png'),fullPage:true});
  await page.getByRole('combobox',{name:'분야',exact:true}).focus();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('combobox',{name:'패턴',exact:true})).toBeFocused();
});
for (const failure of ['404','version','500','malformed','network']) {
  test(`pattern ${failure} keeps legacy exploration available`, async ({page}) => {
    await mock(page);
    await page.route('**/api/dashboard/v1/pattern-catalog', r => failure === 'network' ? r.abort() :
      failure === 'version' ? r.fulfill({json:{schemaVersion:99}}) : failure === 'malformed' ? r.fulfill({json:{schemaVersion:1}}) :
      r.fulfill({status:Number(failure),body:''}));
    await page.goto('/app/catalog?category=data-processing&pattern=bounded-queue');
    await expect(page.locator('.pattern-result')).toHaveCount(16);
    await expect(page.getByRole('combobox',{name:'패턴',exact:true})).toBeDisabled();
    if (failure === '404' || failure === 'version') await expect(page.getByText('모듈별 탐색을 제공합니다.',{exact:false})).toBeVisible();
    else {
      await expect(page.getByRole('alert')).toContainText('패턴 분류 조회 실패');
      await page.route('**/api/dashboard/v1/pattern-catalog', r => r.fulfill({json:metadata}));
      await page.getByRole('button',{name:'분류 다시 시도'}).click();
      await expect(page.locator('.pattern-result')).toHaveCount(2);
    }
    await page.locator('.pattern-result').first().getByRole('link',{name:'실험 설정',exact:true}).click();
    await expect(page.getByRole('combobox',{name:'실험 선택',exact:true})).not.toHaveValue('');
  });
}
test('unknown filters, unclassified extensions, missing modules and empty catalog', async ({page}) => {
  await mock(page);
  await page.route('**/api/experiments',r => r.fulfill({json:[...descriptors,{...descriptors[0],id:'new.extension',title:'New extension'}]}));
  await page.goto('/app/catalog?category=unclassified');
  await expect(page.locator('.pattern-result')).toHaveCount(1);
  await expect(page.locator('.pattern-result')).toContainText('New extension');
  await page.goto('/app/catalog?pattern=unknown');
  await expect(page.getByRole('alert')).toContainText('알 수 없는');
  await expect(page.locator('.pattern-result')).toHaveCount(0);
  await page.getByRole('link',{name:'필터 초기화'}).click();
  await expect(page.locator('.pattern-result')).toHaveCount(17);
  await page.route('**/api/modules',r => r.fulfill({status:404}));
  await page.route('**/api/dashboard/v1/pattern-catalog',r => r.fulfill({status:404}));
  await page.reload();
  await expect(page.locator('.pattern-result')).toHaveCount(17);
  await page.route('**/api/experiments',r => r.fulfill({json:[]}));
  await page.reload();
  await expect(page.locator('.pattern-result')).toHaveCount(0);
  await expect(page.getByText('조건에 맞는 실험이 없습니다.',{exact:false})).toBeVisible();
});
test('actual API discovery and unchanged deep links', async ({page}) => {
  await page.goto('/app/catalog');
  await expect(page.locator('.pattern-result')).toHaveCount(3);
  if (process.env.EXPECT_PATTERN_LEGACY === '1') {
    await expect(page.getByText('이 서버는 패턴 API를 제공하지 않습니다.', {exact:false})).toBeVisible();
    await expect(page.getByRole('combobox',{name:'분야',exact:true})).toBeDisabled();
  } else await expect(page.getByRole('combobox',{name:'분야',exact:true})).toBeEnabled();
  if (process.env.PATTERN_SCREENSHOTS === '1') {
    await page.screenshot({path:'../docs/images/24-pattern-discovery.png',fullPage:true});
    await page.setViewportSize({width:375,height:812});
    await page.reload();
    await page.screenshot({path:'../docs/images/25-pattern-discovery-mobile.png',fullPage:true});
    await page.emulateMedia({reducedMotion:'reduce'});
    await page.evaluate(() => { document.documentElement.style.fontSize = '200%'; });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  await page.goto('/app/experiments/concurrency.queue-contention');
  await expect(page.getByRole('combobox',{name:'실험 선택',exact:true})).toHaveValue('concurrency.queue-contention');
});
