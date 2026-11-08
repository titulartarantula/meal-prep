import { expect, test, type Locator, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

const SHOTS = process.env.SHOTS_DIR ?? (process.env.SHOTS ? "e2e/screens" : null);

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
    await expect(dlg).toContainText("How was Thursday's Lemon orzo");
    await axe(page);
    await phoneChecks(page);
    await dlg.getByRole("radio", { name: "4 stars" }).click();
    await dlg.getByLabel(/Note/).fill("Needs more lemon");
    await dlg.getByRole("button", { name: "Save rating" }).click();
    await expect(dlg).toBeHidden();
    await expect(page.getByRole("article", { name: /^Thursday/ }).getByRole("button", { name: /You rated it 4 of 5/ })).toBeVisible();
  });

  test("this week: the status strip has no prep step; the main action is docked at the bottom on a phone", async ({ page }) => {
    const steps = page.getByRole("list", { name: "Week status" });
    if (!phone()) {
      await expect(steps).toContainText("Cart sent");
      await expect(steps).not.toContainText("Prep");
      await expect(page.locator(".dock")).toHaveCount(0);
      await expect(page.locator(".top-right").getByRole("button", { name: "View cart" })).toBeVisible();
      return;
    }
    await expect(steps).not.toContainText("Prep");
    const dock = page.locator(".dock");
    await expect(dock.getByRole("button", { name: "View cart" })).toBeVisible();
    await expect(page.locator(".top-right").getByRole("button")).toHaveCount(0);
    // docked: right above the tab bar while the nights scroll under it
    const vp = page.viewportSize()!;
    const [d, t] = [await dock.boundingBox(), await page.locator(".tabbar").boundingBox()];
    expect(Math.abs(d!.y + d!.height - t!.y)).toBeLessThanOrEqual(1.5);   // the tab bar's 1 px top border
    expect(Math.round(t!.y + t!.height)).toBe(vp.height);
    await page.getByRole("button", { name: "Next week" }).click();
    await expect(page.locator(".dock").getByRole("button", { name: "Build cart" })).toBeVisible();
    await phoneChecks(page);
    await shot(page, "13-week-docked");
    await page.locator(".dock").getByRole("button", { name: "Build cart" }).click();
    await expect(page.getByRole("heading", { name: "Shopping list" })).toBeVisible();
  });

  test("rating: the How was banner with Later, the company verdict, So far and earlier notes, Good for company", async ({ page }) => {
    const banner = page.getByRole("region", { name: "Rate a dinner" });
    await expect(banner).toContainText(/How was \w+day's /);
    const first = (await banner.locator("b").textContent())!;
    await axe(page);
    await phoneChecks(page);
    await shot(page, "14-rate-banner");
    // Later: the next unrated dinner asks instead, also after a reload (today only)
    await banner.getByRole("button", { name: "Later" }).click();
    await expect(banner.locator("b")).not.toHaveText(first);
    const second = (await banner.locator("b").textContent())!;
    await page.reload();
    await expect(banner.locator("b")).toHaveText(second);
    // Rate it opens the dialog for that dinner
    await banner.getByRole("button", { name: "Rate it" }).click();
    const dlg = page.getByRole("dialog");
    await expect(dlg.getByRole("heading", { level: 2 })).toHaveText(second);
    await dlg.getByRole("radio", { name: "5 stars" }).click();
    await dlg.getByRole("button", { name: "Save rating" }).click();
    await expect(dlg).toBeHidden();
    await expect(banner.locator("b")).not.toHaveText(second);

    // last week's chili: its ratings so far, earlier notes, and (an Owner) the household's company verdict
    await page.getByRole("button", { name: "Previous week" }).click();
    await page.getByRole("article", { name: /^Wednesday/ }).getByRole("button", { name: "How was it?" }).click();
    await expect(dlg.getByRole("heading", { level: 2 })).toHaveText("How was Wednesday's Turkey chili with cornbread?");
    await expect(dlg).toContainText("So far: Average 4.1/5 from 4 ratings · “Kids wanted more cornbread”");   // no company in it
    await expect(dlg.getByRole("heading", { name: "Earlier notes" })).toBeVisible();
    await expect(dlg.getByRole("listitem").filter({ hasText: "A bit too spicy" })).toBeVisible();
    // Good for company: a plain Yes / No toggle below the note, apart from the score (no Maybe, no stars)
    const company = dlg.getByRole("group", { name: "Good for company?", exact: true });
    await expect(company.getByRole("button")).toHaveText(["Yes", "No"]);
    await expect(company.locator(".star")).toHaveCount(0);
    await expect(dlg.locator(".stars-input .company-toggle, .company-toggle .stars-input")).toHaveCount(0);
    for (const v of ["Yes", "No"]) await expect(company.getByRole("button", { name: v })).toHaveAttribute("aria-pressed", "false");
    await company.getByRole("button", { name: "No" }).click();
    await expect(company.getByRole("button", { name: "No" })).toHaveAttribute("aria-pressed", "true");
    await company.getByRole("button", { name: "Yes" }).click();
    await expect(company.getByRole("button", { name: "No" })).toHaveAttribute("aria-pressed", "false");
    await expect(company.getByRole("button", { name: "Yes" })).toHaveAttribute("aria-pressed", "true");
    for (const b of await company.getByRole("button").all()) expect((await b.boundingBox())!.height).toBeGreaterThanOrEqual(48);
    await dlg.getByRole("radio", { name: "4 stars" }).click();
    await dlg.getByLabel(/^Note/).fill("Great with a crowd");
    await axe(page);
    await phoneChecks(page);
    await shot(page, "15-rate-dialog");
    await dlg.getByRole("button", { name: "Save rating" }).click();
    await expect(dlg).toBeHidden();

    // Recipes → Good for company: the chili is now one; the salmon (no answer) isn't
    await page.getByRole("link", { name: "Recipes" }).click();
    await page.getByRole("button", { name: "Good for company", exact: true }).click();
    await expect(page.getByRole("button", { name: "Good for company", exact: true })).toHaveAttribute("aria-pressed", "true");
    const grid = page.getByRole("list", { name: "Recipes" });
    await expect(grid).toContainText("Turkey chili with cornbread");
    await expect(grid).toContainText("Roast chicken with lemon potatoes");
    await expect(grid).not.toContainText("Miso-glazed salmon");
    await axe(page);
    await phoneChecks(page);
    await shot(page, "16-good-for-company");
    await page.getByRole("searchbox", { name: "Search recipes" }).fill("salmon");
    await expect(grid).toContainText("No recipes match.");
  });

  test("rate from the recipe: a card's Rate (today, then update and remove from the drawer), a waiting dinner takes it", async ({ page }) => {
    await page.getByRole("link", { name: "Recipes" }).click();
    const grid = page.getByRole("list", { name: "Recipes" });
    const tofu = grid.getByRole("listitem").filter({ hasText: "Crispy tofu with peanut noodles" });
    await expect(tofu).toContainText("4.3");
    await axe(page);
    await phoneChecks(page);
    // not cooked lately: the day is today
    await page.getByRole("button", { name: "Rate Crispy tofu with peanut noodles" }).click();
    const dlg = page.getByRole("dialog", { name: "Rate Crispy tofu with peanut noodles" });
    await expect(dlg.getByLabel("Cooked on")).toHaveValue(await page.evaluate(() => {
      const d = new Date(), p = (n: number) => String(n).padStart(2, "0");
      return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
    }));
    await expect(dlg).toContainText("Today.");
    await expect(dlg).toContainText("So far: Average 4.3/5 from 3 ratings");
    await expect(dlg.getByRole("button", { name: "Save rating" })).toBeDisabled();
    await dlg.getByRole("radio", { name: "5 stars" }).click();
    await dlg.getByRole("group", { name: "Good for company?", exact: true }).getByRole("button", { name: "Yes" }).click();
    await dlg.getByLabel(/^Note/).fill("Extra lime next time");
    await axe(page);
    await phoneChecks(page);
    await shot(page, "16a-rate-recipe");
    await dlg.getByRole("button", { name: "Save rating" }).click();
    await expect(dlg).toBeHidden();
    await expect(tofu).toContainText("4.5");
    await expect(tofu).toContainText("Good for company");

    // the drawer: Rate, the last rating, the history; rating the same day again updates it, Remove rating takes it away
    await page.getByRole("button", { name: /^Crispy tofu/ }).click();
    const drawer = page.getByRole("dialog", { name: "Crispy tofu with peanut noodles" });
    await expect(drawer).toContainText(/Your last rating: 5\/5 on \w{3} \d+/);
    await expect(drawer.getByRole("list", { name: /^Ratings, / }).filter({ hasText: "Extra lime next time" })).toBeVisible();
    await shot(page, "16b-recipe-drawer-rate");
    await drawer.getByRole("button", { name: "Rate", exact: true }).click();
    await expect(dlg.getByRole("radio", { name: "5 stars" })).toHaveAttribute("aria-checked", "true");
    await expect(dlg.getByLabel(/^Note/)).toHaveValue("Extra lime next time");
    await expect(dlg).toContainText("Already rated for this day");
    await dlg.getByRole("radio", { name: "3 stars" }).click();
    await dlg.getByRole("button", { name: "Update rating" }).click();
    await expect(dlg).toBeHidden();
    await expect(drawer).toContainText("Your last rating: 3/5");
    await drawer.getByRole("button", { name: "Rate", exact: true }).click();
    await dlg.getByRole("button", { name: "Remove rating" }).click();
    await expect(dlg).toBeHidden();
    await expect(drawer.getByText("Extra lime next time")).toHaveCount(0);
    await drawer.getByRole("button", { name: "Close" }).click();
    await expect(tofu).toContainText("4.3");

    // last Wednesday's chili is still waiting for a rating: Rate on its card rates that dinner (no second time cooked)
    await page.getByRole("button", { name: "Rate Turkey chili with cornbread" }).click();
    const chili = page.getByRole("dialog", { name: "Rate Turkey chili with cornbread" });
    await expect(chili).toContainText("This rates Wednesday's dinner on the plan.");
    await chili.getByRole("radio", { name: "4 stars" }).click();
    await shot(page, "16c-rate-waiting-dinner");
    await chili.getByRole("button", { name: "Save rating" }).click();
    await expect(chili).toBeHidden();
    await page.getByRole("link", { name: "This week" }).click();
    await page.getByRole("button", { name: "Previous week" }).click();
    await expect(page.getByRole("article", { name: /^Wednesday/ }).getByRole("button", { name: /You rated it 4 of 5/ })).toBeVisible();
  });

  test("per-person ratings on a dinner: everyone's rating and note on the night, rate for yourself or for a child profile", async ({ page }) => {
    // this week's tacos: Alex rated them, Alex entered Pip's, and the shared phone's (Family) rating
    const night = page.getByRole("article", { name: /^Tuesday/ });
    const people = night.getByRole("list", { name: /^Ratings of Chicken tinga tacos/ });
    await expect(people.getByRole("listitem")).toHaveCount(3);
    await expect(people.getByRole("listitem").nth(0)).toContainText("Alex");
    await expect(people.getByRole("listitem").nth(0)).toContainText("“Extra lime”");
    await expect(people.getByRole("listitem").nth(1)).toContainText(/Pip.*“Picked out the onions”.*entered by Alex/);
    await expect(people.getByRole("listitem").nth(2)).toContainText("Family");
    await expect(night).toContainText("Dinner average 4.7 from 3");
    await axe(page);
    await phoneChecks(page);
    await shot(page, "17-night-people");

    // rate it: "Rating for" Me / Pip; the others' ratings of the dinner are shown
    await night.getByRole("button", { name: /^Options for Chicken tinga tacos/ }).click();
    await page.getByRole("menu").getByRole("menuitem", { name: "Rate dinner" }).click();
    const dlg = page.getByRole("dialog", { name: /Chicken tinga tacos/ });
    const forWhom = dlg.getByRole("radiogroup", { name: "Rating for" });
    await expect(forWhom.getByRole("radio", { name: "Me" })).toHaveAttribute("aria-checked", "true");
    await expect(dlg.getByRole("list", { name: "This dinner so far" }).getByRole("listitem")).toHaveCount(3);
    await expect(dlg).toContainText("Your score: was it a hit?");
    await expect(dlg.getByRole("button", { name: "Save rating" })).toBeDisabled();
    // Pip's rating (entered by Alex) fills in; updating it is entered by you
    await forWhom.getByRole("radio", { name: "Pip" }).click();
    await expect(dlg).toContainText("Pip's score: was it a hit?");
    await expect(dlg.getByRole("radio", { name: "4 stars" })).toHaveAttribute("aria-checked", "true");
    await expect(dlg.getByLabel(/^Note/)).toHaveValue("Picked out the onions");
    await axe(page);
    await phoneChecks(page);
    await shot(page, "18-rate-for-child");
    await dlg.getByRole("radio", { name: "5 stars" }).click();
    await dlg.getByRole("button", { name: "Update rating" }).click();
    await expect(dlg).toBeHidden();
    await expect(people.getByRole("listitem").nth(1)).toContainText(/Pip.*entered by Sam/);
    // and your own
    await night.getByRole("button", { name: /^Options for Chicken tinga tacos/ }).click();
    await page.getByRole("menu").getByRole("menuitem", { name: "Rate dinner" }).click();
    await expect(forWhom.getByRole("radio", { name: "Me" })).toHaveAttribute("aria-checked", "true");
    await dlg.getByRole("radio", { name: "3 stars" }).click();
    await dlg.getByLabel(/^Note/).fill("Too spicy for me");
    await dlg.getByRole("button", { name: "Save rating" }).click();
    await expect(dlg).toBeHidden();
    await expect(night.getByRole("button", { name: /You rated it 3 of 5/ })).toBeVisible();
    await expect(night).toContainText("Dinner average 4.5 from 4");
    await expect(people.getByRole("listitem")).toHaveCount(3);          // yours is the button, not in the list

    // the recipe: a chip per person on its card, and each person's latest + average in the drawer
    await page.getByRole("link", { name: "Recipes" }).click();
    const tacos = page.getByRole("list", { name: "Recipes" }).getByRole("listitem").filter({ hasText: "Chicken tinga tacos" });
    await expect(tacos.locator(".pchip")).toHaveText([/^S3/, /^A5/, /^R4/, /^P5/, "+1"]);   // + the Family's
    await page.getByRole("button", { name: /^Chicken tinga tacos/ }).click();
    const drawer = page.getByRole("dialog", { name: "Chicken tinga tacos with pickled onions" });
    const rows = drawer.getByRole("region", { name: "Ratings" });
    await expect(rows.getByRole("listitem").filter({ hasText: "Sam (you)" })).toContainText("“Too spicy for me”");
    await expect(rows.getByRole("listitem").filter({ hasText: "Pip" })).toContainText("1 rating");
    await expect(rows.getByRole("listitem").filter({ hasText: "Family" })).toContainText("avg 4.8 from 5");
    await expect(drawer).toContainText("Your last rating: 3/5");
    await axe(page);
    await phoneChecks(page);
    await shot(page, "19-drawer-people");
  });

  test("a contributor rates for themselves (no Rating for) and only reads the Good for company verdict", async ({ page, context }) => {
    await context.addCookies([{ name: "mp_mock_member", value: "3", url: "http://localhost:5198" }]);
    await page.goto("/recipes");
    // Robin can rate but can't plan: no add-to-week, no Rating for, no company question (an Owner's)
    const grid = page.getByRole("list", { name: "Recipes" });
    await page.getByRole("button", { name: "Rate Roast chicken with lemon potatoes" }).click();
    const dlg = page.getByRole("dialog", { name: "Rate Roast chicken with lemon potatoes" });
    await expect(dlg.getByRole("radiogroup", { name: "Rating for" })).toHaveCount(0);
    await expect(dlg.getByRole("group", { name: "Good for company?", exact: true })).toHaveCount(0);
    await expect(dlg.locator(".company-badge")).toHaveText("Good for company: Yes");
    await expect(dlg).toContainText("set by Sam");
    await dlg.getByRole("radio", { name: "2 stars" }).click();
    await axe(page);
    await phoneChecks(page);
    await shot(page, "20-contributor-rates");
    await dlg.getByRole("button", { name: "Save rating" }).click();
    await expect(dlg).toBeHidden();
    const chicken = grid.getByRole("listitem").filter({ hasText: "Roast chicken" });
    await expect(chicken.locator(".pchip").filter({ hasText: /^R/ })).toContainText("2");
    await expect(chicken.locator(".company-badge")).toHaveText("Good for company");   // a low score doesn't change the verdict
    await page.getByRole("button", { name: "Good for company", exact: true }).click();
    await expect(grid).toContainText("Roast chicken");
    await page.getByRole("button", { name: /^Chicken tinga tacos/ }).click();
    const drawer = page.getByRole("dialog", { name: /Chicken tinga tacos/ });
    await expect(drawer.getByRole("button", { name: "Add to week" })).toHaveCount(0);
    await expect(drawer.getByRole("group", { name: "Good for company?", exact: true })).toHaveCount(0);
    await expect(drawer.locator(".company-badge")).toHaveText("Good for company: Yes");
  });

  test("an Owner sets the household's Good for company verdict on the recipe", async ({ page }) => {
    await page.getByRole("link", { name: "Recipes" }).click();
    const grid = page.getByRole("list", { name: "Recipes" });
    const salmon = grid.getByRole("listitem").filter({ hasText: "Miso-glazed salmon" });
    await expect(salmon).not.toContainText("Good for company");
    await page.getByRole("button", { name: /^Miso-glazed salmon/ }).click();
    const drawer = page.getByRole("dialog", { name: /^Miso-glazed salmon/ });
    const company = drawer.getByRole("group", { name: "Good for company?", exact: true });
    await expect(company.getByRole("button")).toHaveText(["Yes", "No"]);
    for (const v of ["Yes", "No"]) await expect(company.getByRole("button", { name: v })).toHaveAttribute("aria-pressed", "false");
    await expect(drawer).toContainText("Not decided yet.");
    await company.getByRole("button", { name: "Yes" }).click();
    await expect(company.getByRole("button", { name: "Yes" })).toHaveAttribute("aria-pressed", "true");
    for (const b of await company.getByRole("button").all()) expect((await b.boundingBox())!.height).toBeGreaterThanOrEqual(48);
    await expect(drawer).toContainText("Set by Sam.");
    await axe(page);
    await phoneChecks(page);
    await shot(page, "21-company-verdict");
    await drawer.getByRole("button", { name: "Close" }).click();
    await expect(salmon.locator(".company-badge")).toHaveText("Good for company");
    await page.getByRole("button", { name: "Good for company", exact: true }).click();
    await expect(grid).toContainText("Miso-glazed salmon");
    // choosing it again clears it: not decided, out of the filter
    await page.getByRole("button", { name: /^Miso-glazed salmon/ }).click();
    await company.getByRole("button", { name: "Yes" }).click();
    await expect(company.getByRole("button", { name: "Yes" })).toHaveAttribute("aria-pressed", "false");
    await drawer.getByRole("button", { name: "Close" }).click();
    await expect(grid).not.toContainText("Miso-glazed salmon");
  });

  test("recipes: edit a recipe (409 when it changed meanwhile, discard), edit the source with the book search", async ({ page }) => {
    await page.getByRole("link", { name: "Recipes" }).click();
    await page.getByRole("button", { name: /^Red lentil dal/ }).click();
    const drawer = page.getByRole("dialog", { name: "Red lentil dal with spinach & garlic naan" });
    await expect(drawer).toContainText("From NYT Cooking");   // NYT Cooking is a website
    await expect(drawer.getByRole("link", { name: /^Open on NYT Cooking/ })).toBeVisible();
    await expect(drawer.getByRole("button", { name: "Edit source" })).toHaveCount(0);   // a website has no book
    await drawer.getByRole("button", { name: "Edit recipe" }).click();
    const edit = page.getByRole("dialog", { name: "Edit recipe" });
    await expect(edit.getByLabel("Ingredients")).toHaveValue(/1\.5 cup red lentils/);
    await edit.getByLabel("Title").fill("");
    await edit.getByRole("button", { name: "Save changes" }).click();
    await expect(edit.getByText("The recipe needs a title.")).toBeVisible();
    await edit.getByLabel("Title").fill("Red lentil dal with spinach");
    await edit.getByLabel("Ingredients").fill("1.5 cup red lentils\n1 can coconut milk\nFor the tadka:\n2 tbsp ghee\n1 tsp cumin seeds");
    await edit.getByLabel("Steps").fill("Simmer the lentils.\nFry the tadka and pour it over.");
    await axe(page);
    await phoneChecks(page);
    await shot(page, "17-edit-recipe");
    await edit.getByRole("button", { name: "Save changes" }).click();
    await expect(edit).toBeHidden();
    const renamed = page.getByRole("dialog", { name: "Red lentil dal with spinach" });
    await expect(renamed).toContainText("cumin seeds");
    await expect(renamed).toContainText("Fry the tadka and pour it over.");

    // changed somewhere else after the edit began: 409, the edits stay
    await renamed.getByRole("button", { name: "Edit recipe" }).click();
    await edit.getByLabel("Steps").fill("Simmer.");
    const r = await page.request.patch("/api/recipes/4", { data: { notes: "Changed on a phone" }, headers: { "X-CSRF-Token": "mock-csrf" } });
    expect(r.status()).toBe(200);
    await edit.getByRole("button", { name: "Save changes" }).click();
    await expect(edit.getByRole("alert").filter({ hasText: "since you started editing it" })).toBeVisible();
    await expect(edit.getByLabel("Steps")).toHaveValue("Simmer.");
    await edit.getByRole("button", { name: "Cancel" }).click();
    await expect(edit).toContainText("Discard your changes?");
    await edit.getByRole("button", { name: "Discard" }).click();
    await expect(edit).toBeHidden();
    await renamed.getByRole("button", { name: "Close" }).click();

    // a book recipe: Edit source → book search → pick a book, a page
    await page.getByRole("button", { name: /^Chicken tinga tacos/ }).click();
    const tacos = page.getByRole("dialog", { name: "Chicken tinga tacos with pickled onions" });
    await expect(tacos).toContainText("From: Weeknight Mexican · Ada Example, p. 88");
    await tacos.getByRole("button", { name: "Edit source" }).click();
    const src = page.getByRole("dialog", { name: "Where is it from?" });
    await expect(src.getByRole("radio", { name: "Book" })).toHaveAttribute("aria-checked", "true");
    await src.getByLabel("Book", { exact: true }).fill("pantry");
    await expect(src.getByRole("region", { name: "Book search" })).toContainText("The Invented Pantry");
    await axe(page);
    await phoneChecks(page);
    await shot(page, "18-edit-source");
    await src.getByRole("button", { name: /^The Invented Pantry/ }).click();
    await expect(src.getByLabel("Book", { exact: true })).toHaveValue("The Invented Pantry");
    await expect(src.getByRole("region", { name: "Book search" })).toHaveCount(0);
    await src.getByLabel(/^Page/).fill("12");
    await src.getByRole("button", { name: "Save" }).click();
    await expect(src).toBeHidden();
    await expect(tacos).toContainText("From: The Invented Pantry · Di Placeholder, p. 12");
    await tacos.getByRole("button", { name: "Close" }).click();

    // to another source: a name is needed; "Used before" names fill it
    await page.getByRole("button", { name: /^Turkey chili/ }).click();
    const chili = page.getByRole("dialog", { name: "Turkey chili with cornbread" });
    await chili.getByRole("button", { name: "Edit source" }).click();
    await src.getByRole("radio", { name: "Other" }).click();
    await src.getByRole("button", { name: "Save" }).click();
    await expect(src.getByText("Add a name to save")).toBeVisible();
    await src.getByRole("group", { name: "Used before" }).getByRole("button", { name: "Grandma's cards" }).click();
    await src.getByLabel(/^Note/).fill("blue binder");
    await axe(page);
    await src.getByRole("button", { name: "Save" }).click();
    await expect(chili).toContainText("From: Grandma's cards, blue binder");
  });

  test("account menu: Match the system only when a theme was chosen", async ({ page }) => {
    const menu = page.getByRole("button", { name: "Account and settings" });
    await menu.click();
    await expect(page.getByRole("menuitem", { name: /mode$/ })).toBeVisible();
    await expect(page.getByRole("menuitem", { name: "Match the system" })).toHaveCount(0);
    await page.getByRole("menuitem", { name: /mode$/ }).click();
    await menu.click();
    await page.getByRole("menuitem", { name: "Match the system" }).click();
    await expect(page.locator("html")).not.toHaveAttribute("data-theme", /./);
    await menu.click();
    await expect(page.getByRole("menuitem", { name: /mode$/ })).toBeVisible();
    await expect(page.getByRole("menuitem", { name: "Match the system" })).toHaveCount(0);
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
    await page.getByRole("button", { name: /^Miso-glazed salmon/ }).click();
    await expect(page.getByRole("dialog", { name: "Miso-glazed salmon with sesame greens" })
      .getByRole("img", { name: "Miso-glazed salmon with sesame greens" })).toBeVisible();
    await page.getByRole("dialog").getByRole("button", { name: "Close" }).click();
    await page.getByRole("searchbox", { name: "Search recipes" }).fill("roast chicken");
    await page.getByRole("button", { name: /^Roast chicken with lemon potatoes/ }).click();
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

  test("import a recipe from any site: attributed to the site and author, filtered as Website, not imported twice", async ({ page }) => {
    await page.getByRole("link", { name: "Recipes" }).click();
    await page.getByRole("button", { name: "Add recipe" }).click();
    const dlg = page.getByRole("dialog", { name: "Add a recipe" });
    await expect(dlg.getByRole("tab", { name: "Web link" })).toHaveAttribute("aria-selected", "true");
    const link = dlg.getByLabel("Paste the recipe's link");
    // a LAN link and a site that blocks the server say why
    await link.fill("http://192.168.1.50/recipes/x");
    await dlg.getByRole("button", { name: "Import recipe" }).click();
    await expect(dlg).toContainText("private network");
    await link.fill("https://blocked.example.net/recipes/soup");
    await dlg.getByRole("button", { name: "Import recipe" }).click();
    await expect(dlg).toContainText("blocks automated readers");
    await link.fill("https://www.kitchen.example.com/recipes/invented-apple-crumble?utm_source=share");
    await dlg.getByRole("button", { name: "Import recipe" }).click();
    await expect(dlg).toContainText("Invented apple crumble is in your library (from Example Kitchen · by Pat Example).");
    await axe(page);
    await phoneChecks(page);
    await shot(page, "07b-import-web");
    await dlg.getByRole("button", { name: "Open" }).click();
    const drawer = page.getByRole("dialog", { name: "Invented apple crumble" });
    await expect(drawer).toContainText(/From Example Kitchen( \(the original recipe, opens in a new tab\))? · by Pat Example/);
    const original = drawer.getByRole("link", { name: /^Example Kitchen/ });
    await expect(original).toHaveAttribute("href", "https://kitchen.example.com/recipes/invented-apple-crumble");
    await expect(original).toHaveAttribute("target", "_blank");
    await expect(drawer.getByRole("link", { name: /^Open on Example Kitchen/ })).toBeVisible();
    await expect(drawer.getByRole("button", { name: "Edit source" })).toHaveCount(0);   // where the page came from
    await axe(page);
    await phoneChecks(page);
    await shot(page, "07c-web-recipe");
    await drawer.getByRole("button", { name: "Close" }).click();

    // three source filters: Website / Book / Other. Website shows it with the NYT recipes (only websites); the tile
    // names the site; search finds the site
    const sources = page.getByRole("group", { name: "Source" });
    await expect(sources.getByRole("button")).toHaveText(["All", "Website", "Book", "Other"]);
    await sources.getByRole("button", { name: "Website" }).click();
    const grid = page.getByRole("list", { name: "Recipes" });
    await expect(grid.getByRole("listitem")).toHaveCount(10);
    await expect(grid).toContainText("Example Kitchen");
    await expect(grid).toContainText("NYT Cooking");
    await expect(grid).not.toContainText("Chicken tinga tacos");   // a book
    await sources.getByRole("button", { name: "Book" }).click();
    await expect(grid).toContainText("Chicken tinga tacos");
    await expect(grid).not.toContainText("Example Kitchen");
    await page.getByRole("button", { name: "All", exact: true }).click();
    await page.getByRole("searchbox", { name: "Search recipes" }).fill("example kitchen");
    await expect(grid.getByRole("listitem")).toHaveCount(1);

    // the same page again (another form of the link) opens the saved recipe
    await page.getByRole("button", { name: "Add recipe" }).click();
    await link.fill("http://kitchen.example.com/recipes/invented-apple-crumble/");
    await dlg.getByRole("button", { name: "Import recipe" }).click();
    await expect(dlg).toContainText("Invented apple crumble is already in your library (from Example Kitchen · by Pat Example).");
  });

  test("a night without a recipe: name it, add store items from the chooser, change and remove them; the list and the cart show the night", async ({ page }) => {
    // two weeks ahead: nothing planned yet
    await page.getByRole("button", { name: "Next week" }).click();
    await page.getByRole("button", { name: "Next week" }).click();
    await expect(page.getByRole("article", { name: /^Thursday/ })).toContainText("No dinner yet");
    const week = new URL(page.url()).pathname.split("/").pop()!;
    const thu = page.getByRole("article", { name: /^Thursday/ });

    await thu.getByRole("button", { name: "Options for Thursday" }).click();
    await page.getByRole("menu").getByRole("menuitem", { name: "Name this night" }).click();
    const naming = page.getByRole("dialog", { name: "Name Thursday" });
    await naming.getByLabel("Name of the night").fill("x".repeat(61));
    await expect(naming.getByRole("button", { name: "Save" })).toBeDisabled();
    await naming.getByLabel("Name of the night").fill("  Pizza   night ");
    await axe(page);
    await phoneChecks(page);
    await naming.getByRole("button", { name: "Save" }).click();
    await expect(naming).toBeHidden();
    await expect(thu.getByRole("button", { name: /^Pizza night\. Rename Thursday/ })).toBeVisible();
    await expect(thu).not.toContainText("No dinner yet");                      // a named night counts as planned

    await thu.getByRole("button", { name: "Add items" }).click();
    const chooser = page.getByRole("dialog", { name: "Add to Thursday · Pizza night" });
    const search = async (term: string) => {
      await chooser.getByRole("searchbox", { name: "Search Loblaws" }).fill(term);
      await chooser.getByRole("button", { name: "Search", exact: true }).click();
    };
    await search("pizza");
    await chooser.getByRole("button", { name: /Hearthstone Pepperoni Pizza/ }).click();
    await expect(chooser.getByRole("status")).toContainText("Added Hearthstone Pepperoni Pizza, Rising Crust to Thursday");
    await search("caesar salad");
    await chooser.getByRole("button", { name: /Greenfield Caesar Salad Kit/ }).click();
    await expect(chooser.getByRole("status")).toContainText("Added Greenfield Caesar Salad Kit");
    await axe(page);
    await phoneChecks(page);
    await shot(page, "14-night-chooser");
    await chooser.getByRole("button", { name: "Done" }).click();

    const store = thu.getByRole("group", { name: "From the store, Thursday" });
    await expect(store).toContainText("Hearthstone Pepperoni Pizza");
    await expect(store).toContainText("Greenfield Caesar Salad Kit");
    await store.getByRole("button", { name: /^Options for Hearthstone/ }).click();
    await page.getByRole("menu").getByRole("menuitem", { name: "Add one more" }).click();
    await expect(store.getByRole("listitem").filter({ hasText: "Hearthstone" })).toContainText("×2");
    await store.getByRole("button", { name: /^Options for Greenfield/ }).click();
    await page.getByRole("menu").getByRole("menuitem", { name: "Remove" }).click();
    await expect(store).not.toContainText("Caesar");
    await axe(page);
    await phoneChecks(page);
    await shot(page, "15-night-items");
    // dark mode: the night's name and store items keep their contrast
    await page.getByRole("button", { name: "Account and settings" }).click();
    await page.getByRole("menuitem", { name: "Dark mode" }).click();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
    await axe(page);
    await shot(page, "16-night-items-dark");

    // the shopping list offers the week (it has something to buy) and lists the item with its night
    await page.goto(`/shopping?week=${week}`);
    const added = page.getByRole("region", { name: "Added items" });
    await expect(added).toContainText("Hearthstone Pepperoni Pizza");
    await expect(added).toContainText("×2");
    await expect(added).toContainText("Thursday");
    await axe(page);
    await phoneChecks(page);
    await page.getByRole("button", { name: "Build cart" }).click();
    await expect(page.getByRole("heading", { name: "Review your cart" })).toBeVisible({ timeout: 10_000 });
    await expect(cartLine(page, /Hearthstone Pepperoni Pizza/)).toContainText("Added · Thu");
    await axe(page);

    // back on the week: the item is still on Thursday; rename, then clear the name
    await page.goto(`/week/${week}`);
    await thu.getByRole("button", { name: /^Pizza night/ }).click();
    const rename = page.getByRole("dialog", { name: "Rename Thursday" });
    await rename.getByLabel("Name of the night").fill("Movie night");
    await rename.getByRole("button", { name: "Save" }).click();
    await expect(thu.getByRole("button", { name: /^Movie night/ })).toBeVisible();
    await thu.getByRole("button", { name: "Options for Thursday" }).click();
    await page.getByRole("menu").getByRole("menuitem", { name: "Clear name" }).click();
    await expect(thu.getByRole("button", { name: /^Movie night/ })).toHaveCount(0);
    await expect(store).toContainText("Hearthstone Pepperoni Pizza");
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
