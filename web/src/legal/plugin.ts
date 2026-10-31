// Fills privacy.html and terms.html with the text of docs/web/*.md at build (and dev) time: static pages, no JS.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import type { Plugin } from "vite";
import { legalDoc } from "./markdown.ts";

const DOCS = fileURLToPath(new URL("../../../docs/web/", import.meta.url));
const SOURCES: Record<string, string> = { privacy: "privacy-policy.md", terms: "terms.md" };
const esc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

export function legalPages(): Plugin {
  return {
    name: "mealprep-legal-pages",
    configureServer(server) {
      const files = Object.values(SOURCES).map((f) => DOCS + f);
      server.watcher.add(files);
      server.watcher.on("change", (f) => { if (files.includes(f)) server.ws.send({ type: "full-reload" }); });
    },
    transformIndexHtml(html) {
      return html.replace(/<!--legal:(\w+)-->/, (_, page: string) => {
        const source = SOURCES[page];
        if (!source) throw new Error(`no legal page "${page}"`);
        const doc = legalDoc(readFileSync(DOCS + source, "utf8"));
        if (!doc.title || !doc.updated) throw new Error(`${source} needs a # title and a "_Last updated: …_" line`);
        return `<h1>${esc(doc.title)}</h1>\n<p class="updated">Last updated: ${esc(doc.updated)}</p>\n${doc.html}`;
      });
    },
  };
}
