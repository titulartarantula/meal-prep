import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

const SHOTS = process.env.SHOTS ? "e2e/screens" : null;

async function shot(page: Page, name: string) {
  if (!SHOTS) return;
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${SHOTS}/${name}.png` });
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
    await dlg.getByRole("radio", { name: "4 stars" }).click();
    await dlg.getByLabel(/Note/).fill("Needs more lemon");
    await dlg.getByRole("button", { name: "Save rating" }).click();
    await expect(dlg).toBeHidden();
    await expect(page.getByRole("article", { name: /^Thursday/ }).getByRole("button", { name: /Rated 4 of 5/ })).toBeVisible();
  });

  test("this week: drag a recipe from the tray onto a night", async ({ page }) => {
    const chip = page.locator(".chip", { hasText: "Halloumi grain bowls" });
    await chip.dragTo(page.getByRole("article", { name: /^Saturday/ }));
    await expect(page.getByRole("article", { name: /^Saturday/ })).toContainText("Halloumi grain bowls");
  });

  test("add a dinner to an empty night from the library", async ({ page }) => {
    await page.getByRole("button", { name: /No dinner yet/ }).click();
    const dlg = page.getByRole("dialog");
    await dlg.getByRole("searchbox", { name: "Search recipes" }).fill("shrimp");
    await axe(page);
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
    await page.getByRole("button", { name: "Previous week" }).click();
    await axe(page);
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
    await shot(page, "08-shopping");
    await page.getByRole("button", { name: "Build cart" }).click();

    await expect(page.getByRole("heading", { name: "Building your cart" })).toBeVisible();
    await expect(page.getByText(/Finding products…/)).toBeVisible();
    await shot(page, "09-building");
    await expect(page.getByRole("heading", { name: "Review your cart" })).toBeVisible({ timeout: 10_000 });
    await axe(page);
    await shot(page, "10-cart");

    const row = page.getByRole("row", { name: /Thai Kitchen Rice Stick Noodles/ });
    await row.getByRole("button", { name: "More Rice noodles" }).click();
    await expect(row.getByRole("group", { name: /Quantity/ })).toContainText("2");
    await expect(row).toContainText("$7.98");

    await page.getByRole("row", { name: /Green Onions/ }).getByRole("button", { name: "Remove Green onions" }).click();
    await expect(page.getByRole("row", { name: /Green Onions/ }).getByRole("button", { name: "Put back" })).toBeVisible();

    await page.getByRole("row", { name: /Extra Firm Tofu/ }).getByRole("button", { name: "Swap Extra-firm tofu" }).click();
    const chooser = page.getByRole("dialog", { name: "Choose: Extra-firm tofu" });
    await expect(chooser.getByRole("heading", { name: "In your cart" })).toBeVisible();
    await axe(page);
    await shot(page, "11-chooser");
    await chooser.getByRole("button", { name: /Soyganic Organic Firm Tofu/ }).click();
    await expect(page.getByRole("row", { name: /Soyganic Organic Firm Tofu/ })).toBeVisible();

    await page.getByRole("searchbox", { name: "Add an item" }).fill("strawberries");
    await page.getByRole("searchbox", { name: "Add an item" }).press("Enter");
    await page.getByRole("dialog", { name: "Add: strawberries" }).getByRole("button", { name: /^Strawberries/ }).click();
    await expect(page.getByRole("row", { name: /Strawberries/ })).toContainText("Added");

    await page.getByRole("button", { name: "Send to Loblaws" }).click();
    await expect(page.getByRole("heading", { name: "Cart sent to Loblaws" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Load it on loblaws.ca" })).toBeVisible();
    await expect(page.locator(".bookmarklet")).toHaveAttribute("href", /^javascript:/);
    await axe(page);
    await shot(page, "12-sent");
    await expect(page.getByRole("button", { name: "More Rice noodles" })).toBeDisabled();
  });
});
