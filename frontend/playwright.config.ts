import { defineConfig } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// DASHBOARD_URL targets servers you started yourself; otherwise Playwright starts an
// isolated API (temporary run storage, never the user's .lab) and a Vite dev server.
const external = process.env.DASHBOARD_URL;
const apiPort = process.env.E2E_API_PORT ?? '18080';
const webPort = process.env.E2E_WEB_PORT ?? '4180';
const api = `http://127.0.0.1:${apiPort}`;
// Old-address redirect tests default to port 18080; set API_URL too when E2E_API_PORT changes.
if (!external) process.env.E2E_STORAGE_ROOT ??= mkdtempSync(join(tmpdir(), 'lab-dashboard-e2e-'));

export default defineConfig({
  testDir: 'tests',
  fullyParallel: false,
  workers: 1,
  use: {
    baseURL: external ?? `http://127.0.0.1:${webPort}`,
    browserName: 'chromium',
    viewport: { width: 1440, height: 1000 },
  },
  timeout: 30000,
  webServer: external
    ? undefined
    : [
        {
          command:
            `./gradlew --quiet bootRun --args='--server.port=${apiPort} --server.address=127.0.0.1 ` +
            `--lab.storage.root=${process.env.E2E_STORAGE_ROOT}'`,
          cwd: '..',
          url: api + '/experiments',
          timeout: 240_000,
          reuseExistingServer: false,
          stdout: 'ignore',
          stderr: 'pipe',
        },
        {
          command: `npx vite --host 127.0.0.1 --port ${webPort} --strictPort`,
          url: `http://127.0.0.1:${webPort}/app/`,
          env: { LAB_API_TARGET: api },
          timeout: 60_000,
          reuseExistingServer: false,
        },
      ],
});
