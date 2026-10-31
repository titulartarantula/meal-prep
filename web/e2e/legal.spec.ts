import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

// /privacy and /terms: static pages (privacy.html, terms.html) built from docs/web/*.md, public, no JavaScript needed.
const SHOTS = process.env.SHOTS ? "e2e/screens" : null;

async function axe(page: Page) {
  const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(r.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).slice(0, 3).join(" | ")}`)).toEqual([]);
}

const PAGES = [
  { path: "/privacy", title: "Meal Prep on the web: privacy policy", has: "Google sign-in" },
  { path: "/terms", title: "Meal Prep on the web: terms of service", has: "Grocery carts" },
];

test.describe("without JavaScript", () => {
  test.use({ javaScriptEnabled: false });
  for (const p of PAGES) {
    test(`${p.path} reads without JavaScript or signing in`, async ({ page }) => {
      await page.goto(p.path);
      await expect(page.getByRole("heading", { level: 1, name: p.title })).toBeVisible();
      await expect(page.getByText("Last updated: 31 October 2026")).toBeVisible();
      await expect(page.locator("main")).toContainText(p.has.split(" ")[0]);
      await expect(page.getByRole("link", { name: "titulartarantula@gmail.com" }).first()).toHaveAttribute(
        "href", "mailto:titulartarantula@gmail.com");
      await expect(page.getByRole("navigation", { name: "Site" }).locator('[aria-current="page"]')).toHaveAttribute("href", p.path);
    });
  }
});

for (const scheme of ["light", "dark"] as const) {
  test(`legal pages pass axe (${scheme})`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: scheme });
    for (const p of PAGES) {
      await page.goto(p.path);
      await expect(page.getByRole("heading", { level: 1, name: p.title })).toBeVisible();
      await axe(page);
      if (SHOTS) await page.screenshot({ path: `${SHOTS}/legal${p.path.replace("/", "-")}-${scheme}.png`, fullPage: true });
    }
  });
}

test("the sign-in screen links to both pages", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Privacy policy" }).click();
  await expect(page).toHaveURL(/\/privacy$/);
  await expect(page.getByRole("heading", { level: 1, name: /privacy policy/ })).toBeVisible();
  await page.getByRole("link", { name: "Terms of service" }).click();
  await expect(page).toHaveURL(/\/terms$/);
  await expect(page.getByRole("heading", { level: 1, name: /terms of service/ })).toBeVisible();
  await page.getByRole("link", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("link", { name: "Sign in with Google" })).toBeVisible();
});
