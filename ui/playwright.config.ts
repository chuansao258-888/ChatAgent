import { defineConfig, devices } from "@playwright/test";
import { apiBaseUrl, uiBaseUrl } from "./e2e/helpers/env";

export default defineConfig({
  testDir: "./e2e/specs",
  globalSetup: "./e2e/globalSetup.ts",
  timeout: 60_000,
  expect: {
    timeout: 10_000,
  },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [
    ["list"],
    ["html", { outputFolder: "playwright-report", open: "never" }],
  ],
  use: {
    baseURL: uiBaseUrl,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    viewport: { width: 1366, height: 900 },
    actionTimeout: 15_000,
  },
  webServer: {
    command: "npm run dev -- --host localhost",
    url: uiBaseUrl,
    timeout: 120_000,
    reuseExistingServer: !process.env.CI,
    env: {
      VITE_API_BASE_URL: `${apiBaseUrl}/api`,
    },
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
