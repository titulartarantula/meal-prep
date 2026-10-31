import { defineConfig, devices } from "@playwright/test";

// e2e runs against the mock API (vite --mode mock): no live server, no live database.
// Every spec runs on a laptop and on two phones (the phone layout: bottom tab bar, list of nights, full-screen sheets).
// Both phones run in Chromium (the iPhone gets its size, touch and user agent, not WebKit).
export default defineConfig({
  testDir: "e2e",
  timeout: 30_000,
  workers: 1,
  use: { baseURL: "http://localhost:5198", trace: "retain-on-failure" },
  projects: [
    { name: "desktop", use: { browserName: "chromium", viewport: { width: 1440, height: 900 } } },
    { name: "pixel-7", use: { ...devices["Pixel 7"] } },
    { name: "iphone-14", use: { ...devices["iPhone 14"], browserName: "chromium" } },
  ],
  webServer: { command: "npx vite --mode mock --port 5198 --strictPort", url: "http://localhost:5198", reuseExistingServer: false, timeout: 30_000 },
});
