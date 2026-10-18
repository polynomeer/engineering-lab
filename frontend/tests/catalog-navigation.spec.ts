import {test, expect} from '@playwright/test';

test('category catalog and experience projects have separate navigation', async ({page,request},info) => {
  const modules = await (await request.get('/api/modules')).json();
  const career = modules.filter((m: any) => m.module.collection === 'career');
  const count = career.reduce((n: number,m: any) => n + m.experiments.length,0);
  await page.goto('/app/');
  await expect(page.getByRole('heading',{name:'분야별 카탈로그',exact:true})).toBeVisible();
  await expect(page.getByRole('navigation',{name:'실험 분야'}).getByRole('link')).toHaveCount(7);
  await expect(page.locator('.sidebar .catalog-group')).toHaveCount(0);
  await expect(page.locator('.sidebar a:visible')).toHaveCount(7); // Brand and six main destinations.
  const category = page.getByRole('navigation',{name:'실험 분야'}).getByRole('link',{name:/데이터 처리와 자원 관리/});
  await category.click();
  await expect(page).toHaveURL(/category=data-processing/);
  await expect(page.getByRole('combobox',{name:'분야',exact:true})).toHaveValue('data-processing');
  await page.screenshot({path:info.outputPath('categories.png'),fullPage:false});
  await page.getByRole('navigation',{name:'대시보드 탐색'}).getByRole('link',{name:'경험 사례',exact:true}).click();
  await expect(page.getByRole('heading',{name:'경험 사례',exact:true})).toBeVisible();
  await expect(page.getByRole('navigation',{name:'경험 프로젝트'}).getByRole('link')).toHaveCount(career.length);
  await expect(page.locator('.pattern-result')).toHaveCount(count);
  if (career.length) {
    const module = career[0];
    await page.getByRole('navigation',{name:'경험 프로젝트'}).getByRole('link',{name:new RegExp(module.module.title.replace(/[.*+?^${}()|[\]\\]/g,'\\$&'))}).click();
    await expect(page.locator('.pattern-result')).toHaveCount(module.experiments.length);
    await page.reload();
    await expect(page.getByRole('combobox',{name:'프로젝트 출처'})).toHaveValue(module.module.id);
    await page.locator('.pattern-result').first().getByRole('link',{name:'실험 설정',exact:true}).click();
    await expect(page.getByRole('combobox',{name:'실험 선택',exact:true})).toHaveValue(module.experiments[0].id);
  }
  await page.goto('/app/career');
  await page.screenshot({path:info.outputPath('career.png'),fullPage:false});
  await page.setViewportSize({width:375,height:812});
  await page.reload();
  await expect(page.getByRole('navigation',{name:'대시보드 탐색'})).toBeHidden();
  const menu=page.locator('.navigation-drawer > summary');
  await menu.focus(); await page.keyboard.press('Enter');
  await expect(page.getByRole('navigation',{name:'대시보드 탐색'})).toBeVisible();
  await page.getByText('도구·설정',{exact:true}).click();
  await expect(page.getByRole('link',{name:'현재 DB 조회',exact:true})).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('navigation',{name:'대시보드 탐색'})).toBeHidden();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.screenshot({path:info.outputPath('career-mobile.png'),fullPage:false});
});

test('experience discovery survives a missing pattern API and never guesses missing provenance',async ({page}) => {
  await page.route('**/api/dashboard/v1/pattern-catalog',r=>r.fulfill({status:404}));
  await page.goto('/app/career');
  await expect(page.getByText('이 서버는 패턴 API를 제공하지 않습니다.',{exact:false})).toBeVisible();
  await page.route('**/api/modules',r=>r.fulfill({status:404}));
  await page.reload();
  await expect(page.locator('.pattern-result')).toHaveCount(0);
  await expect(page.getByText('활성화된 경험 사례가 없거나', {exact:false})).toBeVisible();
  await page.getByRole('link',{name:'분야별 카탈로그 보기'}).click();
  await expect(page.locator('.pattern-result').first()).toBeVisible();
});
