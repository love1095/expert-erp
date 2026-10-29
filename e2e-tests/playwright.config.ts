import { defineConfig, devices } from '@playwright/test';

/**
 * Expert ERP end-to-end tests.
 *
 * Expects the app to be running already: frontend on E2E_BASE_URL (default http://localhost:3000),
 * which proxies /api to the backend. Every test registers its own throwaway tenant, so tests are
 * independent of each other and never touch real tenant data.
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  timeout: 30_000,
  expect: { timeout: 10_000 },

  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    // Machine-readable results; the future in-app testing module will import this
    ['json', { outputFile: 'test-results/results.json' }],
  ],

  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },

  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});
