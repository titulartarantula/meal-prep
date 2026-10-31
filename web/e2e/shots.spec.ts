import { expect, test } from "@playwright/test";

// Screenshots of every main screen at phone width, light and dark (mock data): SHOTS_DIR=<dir> npx playwright test
// shots --project=iphone-14. Skipped otherwise.
const DIR = process.env.SHOTS_DIR;

test.skip(!DIR, "set SHOTS_DIR to write screenshots");

for (const scheme of ["light", "dark"] as const) {
  test(`main screens (${scheme})`, async ({ page, request }) => {
    test.setTimeout(90_000);
    await request.get("/api/_mock/reset");
    await page.emulateMedia({ colorScheme: scheme });
    const w = page.viewportSize()!.width;
    const snap = async (name: string, full = false) => {
      await page.waitForTimeout(350);
      await page.screenshot({ path: `${DIR}/${w}-${scheme}-${name}.png`, fullPage: full });
    };

    await page.goto("/");
    await snap("01-sign-in");
    await page.goto("/api/auth/login?next=/week");
    await expect(page.getByRole("heading", { name: "This week" })).toBeVisible();
    await snap("02-week");
    await snap("02-week-full", true);
    await page.getByRole("button", { name: "Place Mushroom bourguignon" }).click();
    await snap("03-week-menu");
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: /No dinner yet/ }).click();
    await snap("04-add-dinner");
    await page.getByRole("button", { name: "Close" }).click();
    await page.getByRole("article", { name: /^Thursday/ }).getByRole("button", { name: "How was it?" }).click();
    await snap("05-rate");
    await page.getByRole("button", { name: "Cancel" }).click();
    await page.getByRole("button", { name: "Account and settings" }).click();
    await snap("06-account-menu");
    await page.keyboard.press("Escape");

    await page.getByRole("link", { name: "Recipes" }).click();
    await expect(page.getByRole("heading", { name: "Recipes" })).toBeVisible();
    await snap("07-recipes");
    await page.getByRole("button", { name: /Roast chicken with lemon potatoes/ }).click();
    await snap("08-recipe");
    await page.getByRole("dialog").getByRole("button", { name: "Close" }).click();
    await page.getByRole("button", { name: "Add recipe" }).click();
    await page.getByRole("dialog").getByRole("tab", { name: "From a file" }).click();
    await page.getByRole("dialog").locator("input[type=file]").setInputFiles({ name: "recipes.json", mimeType: "application/json", buffer: Buffer.from("[]") });
    await expect(page.getByRole("dialog")).toContainText("Spiced carrot soup");
    await snap("09-import-preview");
    await page.getByRole("dialog").getByRole("button", { name: "Close" }).click();

    await page.getByRole("link", { name: "Shopping" }).click();
    await expect(page.getByRole("region", { name: "To buy" })).toBeVisible();
    await snap("10-shopping");
    await snap("10-shopping-full", true);
    await page.getByRole("button", { name: "Build cart" }).click();
    await expect(page.getByText(/Finding products…/)).toBeVisible();
    await snap("11-building");
    await expect(page.getByRole("heading", { name: "Review your cart" })).toBeVisible({ timeout: 10_000 });
    await snap("12-cart");
    await snap("12-cart-full", true);
    await page.getByRole("button", { name: "Options for Extra-firm tofu" }).click();
    await snap("13-cart-line-menu");
    await page.getByRole("menuitem", { name: "Swap product" }).click();
    await expect(page.getByRole("heading", { name: "In your cart" })).toBeVisible();
    await snap("14-chooser");
    await page.getByRole("dialog").getByRole("button", { name: "Close" }).click();
    await page.getByRole("button", { name: "Send to Loblaws" }).click();
    await expect(page.getByRole("heading", { name: "Load it on loblaws.ca" })).toBeVisible();
    await snap("15-sent");
    await snap("15-sent-full", true);

    await page.goto("/privacy");
    await snap("16-privacy");
  });
}

