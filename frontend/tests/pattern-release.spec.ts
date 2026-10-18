import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';

// A live upgraded store is required; no API mocks or new Run submissions.
test('pattern release preserves legacy history and classifies all active experiments', async ({page,request}, info) => {
  test.skip(!process.env.PATTERN_HISTORY, 'Requires a pre-upgrade Run snapshot');
  const old = JSON.parse(readFileSync(process.env.PATTERN_HISTORY!, 'utf8'));
  const experiments = await (await request.get('/api/experiments')).json();
  const ids = experiments.map((e: any) => e.id).sort();
  expect(ids).toHaveLength(16);
  for (const [name,key] of [['pattern','experiments'],['study','studies'],['measurement','contracts']]) {
    const response = await request.get(`/api/dashboard/v1/${name}-catalog`);
    expect(response.ok()).toBe(true);
    const body = await response.json(); expect(body.schemaVersion).toBe(1);
    expect(body[key].map((e: any) => e.experimentId).sort()).toEqual(ids);
  }
  expect(old.length).toBeGreaterThan(1);
  for (const entry of old) {
    const response = await request.get('/api/runs/'+entry.run.runId);
    expect(response.ok()).toBe(true);
    expect(await response.json()).toEqual(entry.run);
  }
  const baseline = old[0].run.runId, candidate = old[1].run.runId;
  const assessment = await (await request.get(`/api/dashboard/v1/comparison-assessment?baseline=${baseline}&candidate=${candidate}`)).json();
  expect(assessment.baselineContract).toBeNull(); expect(assessment.candidateContract).toBeNull();
  expect(assessment.conditions).toContainEqual({field:'contract',status:'UNKNOWN'});
  await page.goto('/app/catalog?pattern=chunked-write');
  await expect(page.locator('.pattern-result')).toHaveCount(3);
  await page.locator('.pattern-result').first().getByText('전략·사례·시나리오',{exact:true}).click();
  await expect(page.locator('.pattern-result').first()).toContainText(/FACT|NEW-DESIGN/);
  await page.screenshot({path:process.env.P7_SCREENSHOTS === '1' ? '../docs/images/26-pattern-release.png' : info.outputPath('pattern-release.png'),fullPage:true});
  await page.setViewportSize({width:375,height:812});
  await page.emulateMedia({reducedMotion:'reduce'});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.getByRole('main').evaluate(element => element.scrollIntoView({block:'start'}));
  await page.screenshot({path:process.env.P7_SCREENSHOTS === '1' ? '../docs/images/27-pattern-release-mobile.png' : info.outputPath('pattern-mobile.png'),fullPage:false});
  await page.setViewportSize({width:1440,height:1000});
  await page.goto(`/app/compare?baseline=${baseline}&candidate=${candidate}`);
  await expect(page.getByRole('region',{name:'측정 계약 비교'})).toBeVisible();
  await page.getByRole('region',{name:'측정 계약 비교'}).screenshot({path:process.env.P7_SCREENSHOTS === '1' ? '../docs/images/28-legacy-contract.png' : info.outputPath('legacy-contract.png')});
});
