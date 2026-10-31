// WCAG AA for the Stone tokens (as the Android theme's contrast tests do): text pairs ≥ 4.5:1, icons/borders ≥ 3:1.
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const css = readFileSync(new URL("./tokens.css", import.meta.url), "utf8");

function block(selector: string): Record<string, string> {
  const i = css.indexOf(selector);
  const body = css.slice(css.indexOf("{", i) + 1, css.indexOf("}", i));
  return Object.fromEntries([...body.matchAll(/--([\w-]+):\s*(#[0-9A-Fa-f]{6})/g)].map((m) => [m[1], m[2]]));
}

function lum(hex: string) {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
const ratio = (a: string, b: string) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };

const TEXT: [string, string][] = [
  ["text", "bg"], ["text", "surface"], ["text", "sunken"], ["text2", "bg"], ["text2", "surface"], ["text2", "sunken"],
  ["text3", "bg"], ["text3", "surface"], ["onprimary", "primary"], ["onprimarysoft", "primarysoft"],
  ["onrequest", "request"], ["onrequestsoft", "requestsoft"], ["ondangersoft", "dangersoft"], ["danger", "surface"],
  ["bg", "text"],   // toasts and the Tonight pill
];
const NON_TEXT: [string, string][] = [["primary", "surface"], ["primary", "bg"], ["focus", "bg"], ["focus", "surface"], ["outline", "surface"]];

for (const [name, sel] of [["light", '[data-theme="light"]'], ["dark", '[data-theme="dark"]']] as const) {
  describe(`Stone ${name}`, () => {
    const t = block(sel);
    it.each(TEXT)("%s on %s ≥ 4.5:1", (fg, bg) => expect(ratio(t[fg], t[bg])).toBeGreaterThanOrEqual(4.5));
    it.each(NON_TEXT)("%s on %s ≥ 3:1", (fg, bg) => expect(ratio(t[fg], t[bg])).toBeGreaterThanOrEqual(3));
  });
}
