# Web vs Android: feature parity

Audit of 2026-10-31: Android app 0.15.0 (vc 23) against the web app at `mealprep.drwhom.ca` (web v1 + NYT photos +
legal pages + the mobile layout). Built from the Android UI code (`android/app/src/main/java/dev/mealprep/app/ui/`) and
the web code (`web/src/`). The web household plan lists fridge, Sunday prep and cook cards as "later on web" (plan item 8).

**Yes** = the web does what the phone does. **Partial** = some of it (what's missing is listed). **No** = not on the web.
**Web only** = the web has it and the phone doesn't.

## Planning the week

| Feature (Android) | Web | Notes |
|---|---|---|
| This week: Sun–Sat nights + "No night yet" tray | Yes | Desktop grid; on phones a list of nights. |
| Week navigation (‹ › with dates, Today) | Yes | Sidebar week list on desktop; ‹ › on phones. |
| Place / move / remove a dinner (menu) | Yes | |
| Drag a dinner between nights | Yes (desktop) | Mouse drag only; on touch screens use the ⋮ menu (HTML drag-and-drop doesn't start from touch). |
| Undo after Remove from week | No | The web removes at once (toast, no Undo). |
| Amount per dinner (Half / Normal / 1½ / Double) | Partial | Shows "×2"; can't change it. |
| Week status strip (Planned · Cart sent · Prep done) | Yes | Hidden on narrow laptops and landscape tablets (900–1180 px). |
| Context button (Build cart / Review cart / View cart) | Yes | Start Sunday prep / Tonight are not offered (no prep on web). |
| Empty week → Add recipes | Yes | |
| "Use up the fridge · N nights free" | No | Fridge isn't on the web. |
| Import cards (Adding X to Recipes, page prompts) | No | The web's imports show their state in the Add a recipe dialog only. |

## Recipes

| Feature (Android) | Web | Notes |
|---|---|---|
| Library list, search (case/accents) | Yes | Web searches title + book title. |
| Sort Newest / Favourites / A–Z | Yes | |
| Source filter (NYT, each book, Unknown book, named Other sources, with counts) | Partial | Web: All / NYT Cooking / Books / Other only; no per-book or per-source entries, no counts. |
| "Good for company" filter | No | |
| Rows: rating, "On the plan", "⚠ Page N missing" | Partial | Rating, times cooked, planned week; no missing-page warning. |
| Recipe photos | Web only | NYT photo links (Android shows none). |
| Recipe detail: ingredients (sub-recipe groups), steps, notes, history, link | Partial | No sub-recipe headings, no description/prep/cook/yield line, no "incl. N from another library". |
| Add to a week (week + optional night) | Yes | |
| Edit recipe (title, servings, lines, steps, notes, times, yield) | No | |
| Edit source / Add book (Book · Other, "Used before") | No | |
| Book search ("Which book?", Open Library / Google Books) | No | |
| Add photo of p.N (cross-referenced pages) | No | |
| Import an NYT link | Yes | |
| Cookbook photo import (camera, multi-page, reorder/retake/delete, Which book? + page) | No | A phone browser could use `<input type=file capture>`; not built. |
| Import from a file: JSON-LD / saved page | Yes | Preview + choices + apply. |
| Import from a file: PDF / Word / text (several recipes per document) | Yes | Same dialog (read job, then preview). |
| Import preview detail (sections, "Replace with the file's version", "Add anyway", Select all/none, AI note) | Partial | One list with tick boxes; no Replace / Add anyway choices, no result lists. |
| Export a recipe / export all (backup file) | No | Server endpoints exist. |

## Ratings

| Feature (Android) | Web | Notes |
|---|---|---|
| Rate a dinner (family 1–5 + note), change, remove | Yes | |
| "Would you make it for company?" Yes / Maybe / No | No | |
| "So far" + earlier notes in the rating screen | No | History list shows on the recipe instead. |
| "How was …?" banner (newest unrated dinner, Later) | No | Past nights show "How was it?" on their card instead. |

## Shopping and cart

| Feature (Android) | Web | Notes |
|---|---|---|
| Shopping list for one or more weeks, To buy / Probably have ticks | Yes | |
| Staples: tick for this list, last bought | Yes | |
| Staples screen (add, edit, every week, reorder, delete) | No | |
| "Use what's in the fridge" switch, "from the fridge" lines | No | |
| Added items (Add an item on the list, per week) | No | Web adds items on the cart only. |
| A cart already made for the week (Open it) | Yes | |
| Build cart, progress, failure | Yes | |
| Review: quantities (incl. grams for weighed items), remove / put back | Yes | |
| Filters New picks / Added / To check / Removed | Web only | |
| Line detail ("why", For recipes, source) | Partial | Need + recipes + note shown; no "why" menu entry. |
| Product chooser: groups (current, bought, deal, other, out of stock) | Yes | |
| Chooser filters (Bought before / On sale / Optimum / Bigger packs) | No | |
| Chooser: Show more (paging), product detail (photo pager, description, stepper) | No | First page only; picking swaps at once. |
| "Covers it: N × size · $" / Bigger pack label | Partial | Cover text shown; no Bigger pack label. |
| Always buy this (pins) | Partial | "Always" tag shown; can't pin or unpin. |
| Pack size dialog ("One pack: …") | No | |
| Add an item to the cart by search | Yes | |
| Stale cart ("Older cart": read-only, Build a new cart) | Partial | Warning + send disabled; no "Build a new cart" button on the cart, and the list hides the stale cart instead of offering "Open old cart". |
| Send to Loblaws | Yes | |
| Loblaws handoff | Partial | Android: in-app WebView sets the cart automatically. Web: copy the cart ID + bookmarklet on a signed-out loblaws.ca tab (laptop). On a phone browser the bookmarklet route is impractical. |
| Sent cart: Open in Loblaws again | Partial | The handoff steps stay on the sent cart's page. |

## Cooking

| Feature (Android) | Web | Notes |
|---|---|---|
| Sunday prep: write the plan, progress, checklist ticks, stale plan / rewrite, previous plan | No | Web reads the plan only for the "Prep done" step. |
| Cook cards: steps with ticks, Timer N min, Print, keep screen on, Rate this dinner, notes since | No | |
| Fridge cleaner: list (amount, as of, use soon, use by), add with list names, edit, remove | No | |
| Fridge photos → review changes | No | |
| Suggest dinners from the fridge | No | |
| Morning "Update the fridge?" nudge | No | |

## App-level

| Feature (Android) | Web | Notes |
|---|---|---|
| Notifications (import done, cart ready, prep ready, thaw, how was dinner, tonight, staples) + settings | No | |
| Offline copies (week, recipes, cards, fridge) + offline banner | No | The web needs a connection; no service worker. |
| Background jobs you can leave (imports, cart builds, prep) | Partial | Server jobs keep running; the web shows progress only while the page is open. |
| Settings: server address + token | n/a | The web is same-origin with a session. |
| Theme | Partial | Android: fixed Garden theme, dark follows the system. Web: Stone theme, Light / Dark / Match the system. |
| Sign in with Google, households, roles, switcher | Web only | Android still uses the shared token (plan Task 7). |
| Role-aware screens (only purchasers build/send, etc.) | Web only | |
| Add to home screen | Web only | Web app manifest + icons (no offline). |
| Privacy policy / terms pages | Yes | Android links the Play-published policy. |

## Summary

- **Same on both:** week plan (place, move, remove), library search/sort, add to a week, NYT link import, file and
  document import, rating with a note, shopping list with staples, build → review → swap → add → send.
- **Phone only, biggest gaps:** cookbook photos, edit recipe / source / book search, export, Sunday prep, cook cards,
  fridge cleaner, notifications, offline, staples editing, pins and pack sizes, chooser filters and detail, company
  verdict and filter.
- **Web only:** Google sign-in + households + roles, recipe photos, cart filters, theme switch, add to home screen.
