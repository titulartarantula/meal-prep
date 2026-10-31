import { defineConfig } from "@playwright/test";

// e2e runs against the mock API (vite --mode mock): no live server, no live database.
export default defineConfig({
  testDir: "e2e",
  timeout: 30_000,
  workers: 1,
  use: { baseURL: "http://localhost:5198", viewport: { width: 1440, height: 900 }, trace: "retain-on-failure" },
  webServer: { command: "npx vite --mode mock --port 5198 --strictPort", url: "http://localhost:5198", reuseExistingServer: false, timeout: 30_000 },
});
