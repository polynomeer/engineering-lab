import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
async function runs(request: any) {
  const ids: string[] = [];
  for (const capacity of [2,4]) {
    const response = await request.post('/api/runs',{data:{schemaVersion:1,experiment:'concurrency.queue-contention',
      scenario:{id:'measurement-'+capacity,hypothesis:'Bounded queue'},parameters:{queueCapacity:capacity,producerThreads:1,consumerThreads:1,itemsPerProducer:10,consumerDelayMs:1},measurement:{mode:'SINGLE_PASS'}}});
    expect(response.status()).toBe(202); const {runId} = await response.json(); ids.push(runId);
    await expect.poll(async () => (await (await request.get('/api/runs/'+runId)).json()).status).toBe('SUCCEEDED');
  }
  return ids;
}
test('stored contracts explain mismatch, preserve raw comparison and fit mobile', async ({page,request},info) => {
  const [a,b] = await runs(request);
  await page.goto(`/app/compare?baseline=${a}&candidate=${b}`);
  const section = page.getByRole('region',{name:'측정 계약 비교'});
  await expect(section).toContainText('조건 차이 검토 필요');
  await expect(section).toContainText('items/s');
  await section.getByText(/조건별 판정/).click();
  await expect(section).toContainText('parameters.queueCapacity: 불일치');
  await section.getByText('공통 검증 성질 (목표, 통과 판정 아님)').first().click();
  await expect(section).toContainText('bounded-memory');
  const download = page.waitForEvent('download');
  await section.getByRole('button',{name:'측정 계약 비교 JSON'}).click();
  const json = JSON.parse(await readFile((await (await download).path())!,'utf8'));
  expect(json.baselineId).toBe(a); expect(json.state).toBe('REVIEW_REQUIRED');
  await expect(page.getByRole('table',{name:'기록된 조건의 차이'})).toBeVisible();
  await section.getByText(/조건별 판정/).click();
  await section.screenshot({path:info.outputPath('measurement-desktop.png')});
  await page.setViewportSize({width:375,height:812});
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await section.screenshot({path:info.outputPath('measurement-mobile.png')});
  await page.setViewportSize({width:812,height:375});
  await page.emulateMedia({reducedMotion:'reduce'});
  await page.addStyleTag({content:'html { font-size: 200%; }'});
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const toggle = section.getByText(/조건별 판정/); await toggle.focus(); await page.keyboard.press('Enter');
  await expect(section.getByText('parameters.queueCapacity: 불일치',{exact:false})).toBeVisible();
});
for (const mode of ['404','500','version','malformed','legacy']) test(`measurement ${mode} preserves raw comparison`,async ({page,request}) => {
  const [a,b] = await runs(request);
  await page.route('**/api/dashboard/v1/comparison-assessment?*',r => mode === 'version' ? r.fulfill({json:{schemaVersion:2}})
    : mode === 'malformed' ? r.fulfill({json:{schemaVersion:1}})
    : mode === 'legacy' ? r.fulfill({json:{schemaVersion:1,baselineId:a,candidateId:b,state:'INSUFFICIENT_EVIDENCE',baselineContract:null,candidateContract:null,conditions:[{field:'contract',status:'UNKNOWN'}],measures:[]}})
    : r.fulfill({status:Number(mode)}));
  await page.goto(`/app/compare?baseline=${a}&candidate=${b}`);
  await expect(page.getByRole('table',{name:'기록된 조건의 차이'})).toBeVisible();
  const section = page.getByRole('region',{name:'측정 계약 비교'});
  if (mode === '500' || mode === 'malformed') {
    await expect(section.getByRole('alert')).toContainText('조회 실패');
    await page.unroute('**/api/dashboard/v1/comparison-assessment?*');
    await section.getByRole('button',{name:'측정 계약 다시 조회'}).click();
    await expect(section).toContainText('조건 차이 검토 필요');
  } else if (mode === 'legacy') await expect(section).toContainText('실행 당시 계약이 없는 기록은 판정 불가');
  else await expect(section).toContainText('기존 비교를 계속 사용할 수 있습니다.');
});
