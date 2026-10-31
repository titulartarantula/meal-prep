/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { mockApi } from "./src/mock/plugin.ts";
import { legalPages } from "./src/legal/plugin.ts";

// Dev: `npm run dev` proxies /api to MEALPREP_API (default the docker server), stripping /api like Caddy does.
// `npm run dev:mock` serves an in-memory API instead (fixtures, nothing leaves the machine) — used by the e2e tests.
// privacy.html and terms.html are static pages built from docs/web/*.md (/privacy and /terms; Caddy tries {path}.html).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const target = env.MEALPREP_API || "http://192.168.1.101:8790";
  return {
    plugins: [react(), legalPages(), ...(mode === "mock" ? [mockApi()] : [])],
    server: {
      port: Number(env.PORT) || 5173,
      strictPort: true,
      proxy: mode === "mock" ? undefined : {
        "/api": { target, changeOrigin: true, rewrite: (p) => p.replace(/^\/api/, "") },
      },
    },
    build: {
      target: "es2022",
      sourcemap: true,
      rolldownOptions: { input: { main: "index.html", privacy: "privacy.html", terms: "terms.html" } },
    },
    test: { include: ["src/**/*.test.ts"] },
  };
});
