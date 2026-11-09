import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  timeout: 90000,
  workers: 1,
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3100",
    headless: true,
    viewport: { width: 1440, height: 1000 },
    launchOptions: process.env.E2E_CHROME_PATH
      ? { executablePath: process.env.E2E_CHROME_PATH }
      : {},
    trace: "retain-on-failure",
  },
  outputDir: process.env.E2E_OUTPUT_DIR ?? "test-results",
});
