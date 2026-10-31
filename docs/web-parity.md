# Web vs Android: feature parity

Audit of 2026-10-31: Android app 0.15.0 (vc 23) against the web app at `mealprep.drwhom.ca` (web v1 + NYT photos +
legal pages + the mobile layout). Built from the Android UI code (`android/app/src/main/java/dev/mealprep/app/ui/`) and
the web code (`web/src/`). The web household plan lists the fridge as "later on web" (plan item 8).

**Update 2026-10-31 (same day):** Sunday prep and cook cards were **retired** on Android, the server and the web
(they don't fit how the household cooks), and these gaps were closed on the web: edit recipe, edit source + book
search, the company verdict, "So far" + earlier notes, the "How was …?" banner, the Good for company filter, the docked
This week action on phones, and the theme menu fix. The rows below are updated.

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
| Week status strip (Planned · Cart sent) | Yes | Hidden on narrow laptops and landscape tablets (900–1180 px). "Prep done" was retired with Sunday prep. |
| Context button (Build cart / Review cart / View cart; Android: Tonight → the recipe) | Yes | Docked above the tab bar on phones, like Android. Tonight isn't offered on the web. |
| Empty week → Add recipes | Yes | |
| "Use up the fridge · N nights free" | No | Fridge isn't on the web. |
| Import cards (Adding X to Recipes, page prompts) | No | The web's imports show their state in the Add a recipe dialog only. |

## Recipes

| Feature (Android) | Web | Notes |
|---|---|---|
| Library list, search (case/accents) | Yes | Web searches title + book title. |
| Sort Newest / Favourites / A–Z | Yes | |
| Source filter (NYT, each book, Unknown book, named Other sources, with counts) | Partial | Web: All / NYT Cooking / Books / Other only; no per-book or per-source entries, no counts. |
| "Good for company" filter | Yes | |
| Rows: rating, "On the plan", "⚠ Page N missing" | Partial | Rating, times cooked, planned week; no missing-page warning. |
| Recipe photos | Web only | NYT photo links (Android shows none). |
| Recipe detail: ingredients (sub-recipe groups), steps, notes, history, link | Partial | No sub-recipe headings, no description/prep/cook/yield line, no "incl. N from another library". |
| Add to a week (week + optional night) | Yes | |
| Edit recipe (title, servings, lines, steps, notes, times, yield) | Yes | Same PATCH with `expected_revision`; a 409 keeps the edits and says why. |
| Edit source / Add book (Book · Other, "Used before") | Yes | |
| Book search ("Which book?", Open Library / Google Books) | Yes | Your books first, then the server's search. |
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
| "Would you make it for company?" Yes / Maybe / No | Yes | Choose the same answer again to clear it. |
| "So far" + earlier notes in the rating screen | Yes | |
| "How was …?" banner (newest unrated dinner, Later) | Yes | Later hides it on that browser until tomorrow. Past nights also show "How was it?" on their card. |
| Rate a recipe from the recipe itself (not from a night on the plan) | Yes (web only) | Web: a star button on each recipe card and Rate in the recipe drawer; "Cooked on" (the dinner of it still waiting for a rating, else today), your last rating. A dinner of it on the plan that day takes the rating, so nothing is counted twice. Server: `PUT` / `DELETE /recipes/{id}/rating`. **Android doesn't have it yet**; it could use the same endpoints later (it already shows these ratings in the recipe's summary). |
| Per-person ratings: each member rates for themselves, a parent rates for a child profile; the family average plus each person's rating and note on the night, the recipe card (chips) and the drawer | Web only | Web: the rating dialogs rate as you, with "Rating for: Me / <child>" for members with rate.for_children; the How was banner asks about dinners **you** haven't rated. Good for company goes by everyone's latest answer (more yes than no). **Android: later** (plan Task 7, with Google sign-in): until then the phones use the shared token, which rates as "Family" (one rater in the average, like every rating from before profiles), still sees a night someone rated on the web as rated (its `rating` falls back to the newest), and its summaries already include everyone's ratings. |

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
| Loblaws handoff | Partial | Android: in-app WebView sets the cart automatically. Web: copy the cart ID + bookmarklet on a signed-out loblaws.ca tab (laptop). On a phone browser the page says to use a laptop or the Android app (kept as is, 2026-10-31). |
| Sent cart: Open in Loblaws again | Partial | The handoff steps stay on the sent cart's page. |

## Cooking

| Feature (Android) | Web | Notes |
|---|---|---|
| Sunday prep: write the plan, progress, checklist ticks, stale plan / rewrite, previous plan | Retired | Removed from Android (0.16.0), the server (routes answer 410, no AI) and the web, 2026-10-31. |
| Cook cards: steps with ticks, Timer N min, Print, keep screen on, Rate this dinner, notes since | Retired | Same. Rating stays on both (rating screen / dialog and the banner). |
| Fridge cleaner: list (amount, as of, use soon, use by), add with list names, edit, remove | No | |
| Fridge photos → review changes | No | |
| Suggest dinners from the fridge | No | |
| Morning "Update the fridge?" nudge | No | |

## App-level

| Feature (Android) | Web | Notes |
|---|---|---|
| Notifications (import done, cart ready, how was dinner, tonight, staples) + settings | No | Prep ready and thaw reminders were retired with Sunday prep. |
| Offline copies (week, recipes, fridge) + offline banner | No | The web needs a connection; no service worker. |
| Background jobs you can leave (imports, cart builds) | Partial | Server jobs keep running; the web shows progress only while the page is open. |
| Settings: server address + token | n/a | The web is same-origin with a session. |
| Theme | Partial | Android: fixed Garden theme, dark follows the system. Web: Stone theme, Light / Dark / Match the system (offered only once a theme was chosen). |
| Sign in with Google, households, roles, switcher | Web only | Android still uses the shared token (plan Task 7). |
| Role-aware screens (only purchasers build/send, etc.) | Web only | |
| Add to home screen | Web only | Web app manifest + icons (no offline). |
| Privacy policy / terms pages | Yes | Android links the Play-published policy. |

## Summary

- **Same on both:** week plan (place, move, remove), library search/sort, add to a week, NYT link import, file and
  document import, edit recipe and source (book search), rating with company verdict, note, So far and earlier notes,
  the How was banner, Good for company, shopping list with staples, build → review → swap → add → send.
- **Phone only, biggest gaps:** cookbook photos, export, fridge cleaner, notifications, offline, staples editing, pins
  and pack sizes, chooser filters and detail.
- **Retired everywhere (2026-10-31):** Sunday prep and cook cards.
- **Web only:** Google sign-in + households + roles, per-person ratings, recipe photos, cart filters, theme switch, add to home screen.
