import { test, expect } from '@playwright/test';
test('opt-in case uses existing discovery, generic form, evidence and comparison',async ({page,request}) => {
  test.skip(process.env.CHECKSUM_E2E !== '1', 'Requires API with lab.examples.checksum.enabled=true');
  await page.goto('/app/catalog?pattern=chunked-write');
  const card=page.locator('.pattern-result').filter({has:page.getByRole('heading',{name:'Batched checksum',exact:true})});
  await expect(card).toBeVisible();
  await card.getByText('전략·사례·시나리오',{exact:true}).click();
  await expect(card).toContainText('NEW-DESIGN');
  await card.getByRole('link',{name:'실험 설정',exact:true}).click();
  await expect(page.getByRole('combobox',{name:'실험 선택',exact:true})).toHaveValue('examples.batched-checksum');
  await page.getByLabel('가설',{exact:true}).fill('Batch size preserves generated record checksum');
  const ids:string[]=[];
  for (const [size,fail] of [[1,0],[8,0],[8,2]]) {
    await page.getByLabel('시나리오 ID',{exact:false}).fill(`checksum-${size}-${fail}`);
    for (const [key,value] of Object.entries({rows:23,batchSize:size,seed:7,failBatch:fail}))
      await page.getByLabel(new RegExp('^'+key+' \\(')).fill(String(value));
    const submitted=page.waitForResponse(r=>r.url().endsWith('/api/runs') && r.request().method()==='POST');
    await page.getByRole('button',{name:'실험 실행',exact:true}).click();
    const response=await submitted; expect(response.status()).toBe(202);
    const {runId}=await response.json(); ids.push(runId);
    await expect.poll(async ()=>(await (await request.get('/api/runs/'+runId)).json()).status).toBe(fail ? 'FAILED':'SUCCEEDED');
  }
  await page.goto(`/app/compare?baseline=${ids[0]}&candidate=${ids[1]}`);
  await expect(page.getByRole('region',{name:'측정 계약 비교'})).toContainText('조건 차이 검토 필요');
  await expect(page.getByRole('table',{name:'기록된 조건의 차이'})).toBeVisible();
  await page.goto('/app/runs/'+ids[2]);
  await expect(page.getByRole('heading',{name:'checksum-8-2',exact:true})).toBeVisible();
  const response=await request.get('/api/runs/'+ids[2]+'/export');
  expect(response.ok()).toBe(true); expect(await response.text()).toContain('checksum.rows');
  await page.setViewportSize({width:375,height:812});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
