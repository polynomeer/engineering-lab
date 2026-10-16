import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
const fixture = JSON.parse(
  readFileSync('../contracts/dashboard/v1/fixtures/evidence.json', 'utf8'),
);

test('Dark Observatory preserves evidence while switching views and collapsing mobile navigation', async ({
  page,
}) => {
  const id = fixture.run.runId;
  await page.route('**/api/experiments', (r) =>
    r.fulfill({
      json: [
        {
          id: 'career.mcp-platform.architecture',
          title: 'Architecture',
          category: 'CAREER',
          input: 'NONE',
          parameters: {},
          measurementModes: ['SINGLE_PASS'],
        },
      ],
    }),
  );
  await page.route('**/api/modules', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/runs/ui/options', (r) =>
    r.fulfill({ json: { pageSize: 20, artifactDownloadMaxBytes: 1048576 } }),
  );
  await page.route('**/api/dashboard/v1/capabilities', (r) =>
    r.fulfill({
      json: {
        schemaVersion: 1,
        features: { executionEvidence: true, checksRead: true, extendedExport: true },
      },
    }),
  );
  await page.route('**/api/runs/' + id, (r) =>
    r.fulfill({
      json: {
        ...fixture.run,
        experimentType: 'career.mcp-platform.architecture',
        scenarioId: 'Architecture · COMPLIANT',
        runMetadata: {
          ...fixture.run.runMetadata,
          parameters: { 'scenario.hypothesis': '레이어 의존 방향과 패키지 경계를 검증합니다.' },
        },
        summary: { counters: {}, gauges: {}, details: {} },
      },
    }),
  );
  await page.route('**/api/runs/' + id + '/artifacts', (r) => r.fulfill({ json: [] }));
  await page.route('**/api/runs/' + id + '/metrics?*', (r) =>
    r.fulfill({ json: { metrics: [], nextAfter: null } }),
  );
  await page.route('**/api/dashboard/v1/runs/' + id + '/execution', (r) =>
    r.fulfill({
      json: {
        available: true,
        execution: {
          definition: {
            kind: 'CONTAINER',
            moduleId: 'career.mcp-platform',
            measurementScope: 'CHECK_RESULTS',
          },
          phase: 'COMPLETED',
          evidenceComplete: true,
          worker: {
            environment: { javaVersion: '21', osName: 'Linux' },
            git: { commit: 'a31f92c' },
            artifactSha256: '9f3c7b4d',
          },
        },
      },
    }),
  );
  await page.route('**/api/dashboard/v1/runs/' + id + '/checks?*', (r) =>
    r.fulfill({
      json: {
        available: true,
        nextAfter: null,
        checks: [
          {
            sequence: 0,
            checkId: 'layer-direction',
            description: '레이어 의존 방향',
            status: 'PASS',
            expected: '규칙 준수',
            observed: '위반 없음',
          },
          {
            sequence: 1,
            checkId: 'package-boundaries',
            description: '패키지 경계',
            status: 'PASS',
            expected: '규칙 준수',
            observed: '위반 없음',
          },
        ],
      },
    }),
  );
  await page.route('**/api/runs?*', (r) =>
    r.fulfill({
      json: {
        runs: [
          { ...fixture.run, runId: id, scenarioId: 'Architecture · COMPLIANT' },
          {
            ...fixture.run,
            runId: 'architecture-violation',
            scenarioId: 'Architecture · VIOLATION',
            status: 'FAILED',
          },
          { ...fixture.run, runId: 'architecture-previous', scenarioId: 'Architecture · Previous' },
        ],
        nextBefore: null,
      },
    }),
  );
  await page.goto('/app/runs/' + id);
  const checks = page.getByRole('table', { name: '기대값과 관측값' });
  await expect(checks).toBeVisible();
  await expect(page.getByRole('button', { name: '검증 결과', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await expect(page.getByRole('heading', { name: '가설', exact: true })).toBeHidden();
  await expect(page.getByText('현재 페이지 · 2 PASS · 0 FAIL')).toBeVisible();
  await page.getByRole('button', { name: '검증 결과', exact: true }).click();
  await expect(checks).toBeVisible();
  await expect(page.getByRole('table', { name: '실제 실행 정보' })).toBeVisible();
  await page.getByRole('button', { name: '실행 환경', exact: true }).click();
  await expect(checks).toBeHidden();
  await expect(page.getByRole('table', { name: '실제 실행 정보' })).toBeVisible();
  await page.getByRole('button', { name: '검증 결과', exact: true }).click();
  await expect(checks).toBeVisible();
  await page.getByRole('checkbox', { name: 'Architecture · COMPLIANT 비교 선택' }).check();
  await page.getByRole('checkbox', { name: 'Architecture · VIOLATION 비교 선택' }).check();
  await expect(
    page.getByRole('checkbox', { name: 'Architecture · Previous 비교 선택' }),
  ).toBeDisabled();
  await expect(page.getByRole('link', { name: '선택한 2개 Run 비교' })).toHaveAttribute(
    'href',
    '/app/compare?baseline=' + id + '&candidate=architecture-violation',
  );
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: '../docs/images/20-dark-observatory-desktop.png', fullPage: true });
  for (const width of [1024, 768, 375]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  }
  const menu = page.locator('.navigation-drawer > summary');
  await menu.click();
  await expect(page.getByRole('navigation', { name: '대시보드 탐색' })).toBeHidden();
  await menu.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('navigation', { name: '대시보드 탐색' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(menu).toBeFocused();
  await expect(page.getByRole('navigation', { name: '대시보드 탐색' })).toBeHidden();
  await page.screenshot({ path: '../docs/images/21-dark-observatory-mobile.png', fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect(page.getByRole('navigation', { name: '대시보드 탐색' })).toBeVisible();
});
