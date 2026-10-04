import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: 'tests',
  fullyParallel: false,
  workers: 1,
  use: {
    baseURL: process.env.DASHBOARD_URL ?? 'http://127.0.0.1:5173',
    browserName: 'chromium',
    viewport: { width: 1440, height: 1000 },
  },
  timeout: 30000,
});
