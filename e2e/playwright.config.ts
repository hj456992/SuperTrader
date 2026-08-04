import { defineConfig } from '@playwright/test';

// Playwright config for the Agent Demo E2E. Tests run against an already
// started local demo (backend :8080 + frontend :5174) launched by
// run-agent-demo.sh. They NEVER connect SimNow / CTP / Gateway.
export default defineConfig({
  testDir: '.',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:5174',
    trace: 'on-first-retry',
    actionTimeout: 10_000,
  },
  projects: [
    {
      name: 'desktop',
      testMatch: /agent-demo\.spec\.ts/,
      use: { viewport: { width: 1280, height: 800 } },
    },
    {
      name: 'mobile',
      testMatch: /mobile\.spec\.ts/,
      use: { viewport: { width: 390, height: 844 } },
    },
  ],
});
