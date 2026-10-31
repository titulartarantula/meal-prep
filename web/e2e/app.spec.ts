import { expect, test, type Locator, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

const SHOTS = process.env.SHOTS ? "e2e/screens" : null;

async function shot(page: Page, name: string) {
  if (!SHOTS) return;
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${SHOTS}/${test.info().project.name}-${name}.png` });
}

/** Phone projects get the phone layout (bottom tab bar, cart rows with a ⋮ menu). */
const phone = () => test.info().project.name !== "desktop";

/** Phone layout checks on the current screen: nothing wider than the screen, every control at least 48 px. */
async function phoneChecks(page: Page) {
  if (!phone()) return;
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
  const small = await page.evaluate(() => {
    const out: string[] = [];
    // Inline text links and the rating pills keep their look and get a bigger touch area from ::after (app.css).
    const grown = ".rate-cta, .rated, .link-btn";
    for (const el of document.querySelectorAll<HTMLElement>("button, select, input:not([type=checkbox]), [role=menuitem], .tabbar a")) {
      if (el.closest(grown) || el.closest("[hidden], dialog:not([open])")) continue;
      const r = el.getBoundingClientRect();
      if (!r.width || !r.height) continue;
      if (Math.min(r.width, r.height) < 47.5) out.push(`${el.tagName.toLowerCase()} "${(el.getAttribute("aria-label") ?? el.textContent ?? "").trim().slice(0, 30)}" ${Math.round(r.width)}×${Math.round(r.height)}`);
    }
    return out;
  });
  expect(small).toEqual([]);
}

/** A cart line: a table row on a laptop, a list item on a phone. */
function cartLine(page: Page, name: RegExp): Locator {
  return phone() ? page.getByRole("list", { name: "Cart lines" }).getByRole("listitem").filter({ hasText: name }) : page.getByRole("row", { name });
}

/** A line action: its own button on a laptop, an entry of the line's ⋮ menu on a phone. */
async function lineAction(page: Page, line: Locator, item: string, desktopButton: string) {
  if (!phone()) return line.getByRole("button", { name: desktopButton }).click();
  await line.getByRole("button", { name: /^Options for / }).click();
  await page.getByRole("menu").getByRole("menuitem", { name: item }).click();
}

/** WCAG 2.x A/AA rules on the current page (dialogs included when open). */
async function axe(page: Page) {
  const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(r.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).slice(0, 3).join(" | ")}`)).toEqual([]);
}

test.beforeEach(async ({ request }) => {
  await request.get("/api/_mock/reset");
});

test("sign-in page, then Google sign-in lands on the week", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Meal Prep" })).toBeVisible();
  await axe(page);
  await phoneChecks(page);
  await shot(page, "01-sign-in");
  await page.getByRole("link", { name: "Sign in with Google" }).click();
  await expect(page.getByRole("heading", { name: "This week" })).toBeVisible();
});

test.describe("signed in", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/api/auth/login?next=/week");
    await expect(page.getByRole("heading", { name: "This week" })).toBeVisible();
  });

  test("this week: nights, tray, place from the tray, rate a dinner", async ({ page }) => {
    await expect(page.getByRole("article", { name: /^Sunday/ })).toContainText("Sheet-pan gnocchi");
    await expect(page.getByRole("article", { name: "No night yet" })).toContainText("Mushroom bourguignon");
    await axe(page);
    await phoneChecks(page);
    await shot(page, "02-week");

    // Place the bourguignon on Saturday from its menu.
    await page.getByRole("button", { name: "Place Mushroom bourguignon" }).click();
    await page.getByRole("menu").getByRole("menuitem", { name: "Sat" }).click();
    await expect(page.getByRole("article", { name: /^Saturday/ })).toContainText("Mushroom bourguignon");
    await expect(page.getByRole("article", { name: "No night yet" })).not.toContainText("Mushroom bourguignon");

    // Rate Thursday's dinner.
    await page.getByRole("article", { name: /^Thursday/ }).getByRole("button", { name: "How was it?" }).click();
    const dlg = page.getByRole("dialog");
    await expect(dlg).toContainText("How was Lemon orzo");
    await axe(page);
    await phoneChecks(page);
    await dlg.getByRole("radio", { name: "4 stars" }).click();
    await dlg.getByLabel(/Note/).fill("Needs more lemon");
    await dlg.getByRole("button", { name: "Save rating" }).click();
    await expect(dlg).toBeHidden();
    await expect(page.getByRole("article", { name: /^Thursday/ }).getByRole("button", { name: /Rated 4 of 5/ })).toBeVisible();
  });

  test("this week: drag a recipe from the tray onto a night", async ({ page }) => {
    test.skip(phone(), "Touch layouts place dinners with the menu (HTML drag-and-drop doesn't start from a finger).");
    const chip = page.locator(".chip", { hasText: "Halloumi grain bowls" });
    await chip.dragTo(page.getByRole("article", { name: /^Saturday/ }));
    await expect(page.getByRole("article", { name: /^Saturday/ })).toContainText("Halloumi grain bowls");
  });

  test("add a dinner to an empty night from the library", async ({ page }) => {
    await page.getByRole("button", { name: /No dinner yet/ }).click();
    const dlg = page.getByRole("dialog");
    await dlg.getByRole("searchbox", { name: "Search recipes" }).fill("shrimp");
    await axe(page);
    await phoneChecks(page);
    await shot(page, "03-add-dinner");
    await dlg.getByRole("button", { name: /Shrimp fried rice/ }).click();
    await expect(page.getByRole("article", { name: /^Saturday/ })).toContainText("Shrimp fried rice");
    await expect(page.getByRole("status").filter({ hasText: "Added Shrimp fried rice" })).toBeVisible();
  });

  test("week navigation and dark mode", async ({ page }) => {
    await page.getByRole("button", { name: "Next week" }).click();
    await expect(page.getByRole("heading", { name: "Next week" })).toBeVisible();
    await expect(page.getByRole("article", { name: /^Sunday/ })).toContainText("Crispy tofu");
    await page.getByRole("button", { name: "Account and settings" }).click();
    await page.getByRole("menuitem", { name: "Dark mode" }).click();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
    await expect(page.locator('meta[name="theme-color"]').first()).toHaveAttribute("content", "#171615");
    await page.getByRole("button", { name: "Previous week" }).click();
    await axe(page);
    await phoneChecks(page);
    await shot(page, "04-week-dark");
  });

  test("recipes: search, open, add to a week; import a link", async ({ page }) => {
    await page.getByRole("link", { name: "Recipes" }).click();
    await expect(page.getByRole("heading", { name: "Recipes" })).toBeVisible();
    // a recipe's photo loads; a link that fails and a recipe without one both show the placeholder
    const tile = (name: string) => page.getByRole("listitem").filter({ has: page.getByRole("button", { name }) });
    await expect(tile("Sheet-pan gnocchi with sausage & peppers").locator("img.photo")).toHaveJSProperty("complete", true);
    expect(await tile("Sheet-pan gnocchi with sausage & peppers").locator("img.photo").evaluate((i: HTMLImageElement) => i.naturalWidth)).toBeGreaterThan(0);
    await expect(tile("Lemon orzo with peas, feta & mint").locator(".photo.placeholder")).toBeVisible();
    await expect(tile("Lemon orzo with peas, feta & mint").locator("img")).toHaveCount(0);
    await expect(tile("Halloumi grain bowls").locator(".photo.placeholder")).toBeVisible();
    await axe(page);
    await phoneChecks(page);
    await shot(page, "05-recipes");
    await page.getByRole("button", { name: /Miso-glazed salmon/ }).click();
    await expect(page.getByRole("dialog", { name: "Miso-glazed salmon with sesame greens" })
      .getByRole("img", { name: "Miso-glazed salmon with sesame greens" })).toBeVisible();
    await page.getByRole("dialog").getByRole("button", { name: "Close" }).click();
    await page.getByRole("searchbox", { name: "Search recipes" }).fill("roast chicken");
    await page.getByRole("button", { name: /Roast chicken with lemon potatoes/ }).click();
    const drawer = page.getByRole("dialog", { name: "Roast chicken with lemon potatoes" });
    await expect(drawer.getByRole("heading", { name: "Ingredients" })).toBeVisible();
    await axe(page);
    await phoneChecks(page);
    await shot(page, "06-recipe");
    await drawer.getByRole("button", { name: "Add to week" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Added to next week" })).toBeVisible();
    await drawer.getByRole("button", { name: "Close" }).click();

    await page.getByRole("button", { name: "Add recipe" }).click();
    const dlg = page.getByRole("dialog", { name: "Add a recipe" });
    await dlg.getByLabel("Paste the recipe's link").fill("https://cooking.nytimes.com/recipes/1009-crispy-tofu");
    await dlg.getByRole("button", { name: "Import recipe" }).click();
    await expect(dlg).toContainText("is already in your library");
    await dlg.getByRole("tab", { name: "From a file" }).click();
    await dlg.locator("input[type=file]").setInputFiles({ name: "recipes.json", mimeType: "application/json", buffer: Buffer.from("[]") });
    await expect(dlg).toContainText("Spiced carrot soup");
    await axe(page);
    await phoneChecks(page);
    await shot(page, "07-import-preview");
    await dlg.getByRole("button", { name: "Import 2" }).click();
    await expect(dlg).toContainText("Done: 2 added", { timeout: 10_000 });
  });

  test("shopping list → build cart → review, swap, add, send → handoff", async ({ page }) => {
    await page.getByRole("link", { name: "Shopping" }).click();
    await expect(page.getByRole("region", { name: "To buy" })).toContainText("Rice noodles");
    await page.getByRole("checkbox", { name: /^Rice noodles/ }).uncheck();
    await expect(page.getByRole("region", { name: "Probably have" })).toContainText("Rice noodles");
    await expect(page.getByRole("region", { name: "Probably have" })).toBeVisible();
    await axe(page);
    await phoneChecks(page);
    await shot(page, "08-shopping");
    await page.getByRole("button", { name: "Build cart" }).click();

    await expect(page.getByRole("heading", { name: "Building your cart" })).toBeVisible();
    await expect(page.getByText(/Finding products…/)).toBeVisible();
    await shot(page, "09-building");
    await expect(page.getByRole("heading", { name: "Review your cart" })).toBeVisible({ timeout: 10_000 });
    await axe(page);
    await phoneChecks(page);
    await shot(page, "10-cart");

    const row = cartLine(page, /Thai Kitchen Rice Stick Noodles/);
    await row.getByRole("button", { name: "More Rice noodles" }).click();
    await expect(row.getByRole("group", { name: /Quantity/ })).toContainText("2");
    await expect(row).toContainText("$7.98");

    await lineAction(page, cartLine(page, /Green Onions/), "Remove from cart", "Remove Green onions");
    await expect(cartLine(page, /Green Onions/).getByRole("button", { name: "Put back" })).toBeVisible();

    await lineAction(page, cartLine(page, /Extra Firm Tofu/), "Swap product", "Swap Extra-firm tofu");
    const chooser = page.getByRole("dialog", { name: "Choose: Extra-firm tofu" });
    await expect(chooser.getByRole("heading", { name: "In your cart" })).toBeVisible();
    await axe(page);
    await phoneChecks(page);
    await shot(page, "11-chooser");
    await chooser.getByRole("button", { name: /Soyganic Organic Firm Tofu/ }).click();
    await expect(cartLine(page, /Soyganic Organic Firm Tofu/)).toBeVisible();

    await page.getByRole("searchbox", { name: "Add an item" }).fill("strawberries");
    await page.getByRole("searchbox", { name: "Add an item" }).press("Enter");
    await page.getByRole("dialog", { name: "Add: strawberries" }).getByRole("button", { name: /^Strawberries/ }).click();
    await expect(cartLine(page, /Strawberries/)).toContainText("Added");

    await page.getByRole("button", { name: "Send to Loblaws" }).click();
    await expect(page.getByRole("heading", { name: "Cart sent to Loblaws" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Load it on loblaws.ca" })).toBeVisible();
    await expect(page.locator(".bookmarklet")).toHaveAttribute("href", /^javascript:/);
    await axe(page);
    await phoneChecks(page);
    await shot(page, "12-sent");
    await expect(page.getByRole("button", { name: "More Rice noodles" })).toBeDisabled();
  });

  test("layout: sidebar on a laptop, top bar + bottom tabs on a phone; the tabs switch screens", async ({ page }) => {
    const tabs = page.getByRole("navigation", { name: "Main" });
    if (!phone()) {
      await expect(page.getByRole("complementary", { name: "Main" })).toBeVisible();
      await expect(page.locator(".tabbar")).toHaveCount(0);
      return;
    }
    await expect(page.getByRole("complementary", { name: "Main" })).toHaveCount(0);
    const box = await tabs.boundingBox();
    const vp = page.viewportSize()!;
    expect(Math.round(box!.y + box!.height)).toBe(vp.height);   // docked to the bottom
    await expect(tabs.getByRole("link", { name: "This week" })).toHaveAttribute("aria-current", "page");
    // the menu opens as a sheet and closes with Esc, giving focus back to its button
    await page.getByRole("button", { name: "Account and settings" }).click();
    await expect(page.getByRole("menu")).toBeVisible();
    await phoneChecks(page);
    await page.keyboard.press("Escape");
    await expect(page.getByRole("menu")).toBeHidden();
    await expect(page.getByRole("button", { name: "Account and settings" })).toBeFocused();
    await tabs.getByRole("link", { name: "Recipes" }).click();
    await expect(page.getByRole("heading", { name: "Recipes" })).toBeVisible();
    await phoneChecks(page);
    await tabs.getByRole("link", { name: "Shopping" }).click();
    await expect(page.getByRole("heading", { name: "Shopping list" })).toBeVisible();
    await expect(tabs.getByRole("link", { name: "Shopping" })).toHaveAttribute("aria-current", "page");
    // full-screen sheet for a dialog
    await tabs.getByRole("link", { name: "This week" }).click();
    await page.getByRole("button", { name: /No dinner yet/ }).click();
    const dlg = await page.getByRole("dialog").boundingBox();
    expect([Math.round(dlg!.width), Math.round(dlg!.height)]).toEqual([vp.width, vp.height]);
  });

  test("the web app manifest and icons", async ({ request }) => {
    const m = await (await request.get("/manifest.json")).json();
    expect(m).toMatchObject({ name: "Meal Prep", start_url: "/week", display: "standalone" });
    for (const i of m.icons) expect((await request.get(i.src)).ok()).toBe(true);
  });
});
