# UX and WCAG 2.2 AA review — app 0.7.1

Scope: every screen of the Android app as of 0.7.1 (versionCode 14), including what came after the 0.4.2 review:
the Garden theme (0.6.0), Sunday prep and cook cards (0.5.0), ratings, the "How was dinner?" banner and
notification settings with Coming up (0.7.0), and Other sources (0.7.1). It follows up on
[ux-review-0.4.md](ux-review-0.4.md): every 0.4 backlog item is re-checked below. Nothing in the app was changed for
this review.

## Method

- Screens rendered from the app's own composables with synthetic data in a throwaway Robolectric harness (native
  graphics, window views drawn to bitmaps, dialog and popup windows composited on top; not committed) at a Pixel
  9-like size (1080 × 2424 px, 420 dpi ≈ 411 × 923 dp), font scale 1.0 and 2.0 (set on the configuration, so
  Android's non-linear scaling applies and dialogs scale too), light and dark: 40 screens and states, 160 images.
  Screens: This week (planned with the rating banner, empty, import cards + offline, a past week, load error, entry
  dialog), Shopping list, Cart review (ready, building, Swap), Loblaws handoff (ready, failed with details, close
  confirmation), Recipes (list, source menu, empty, no company favourites), recipe detail, Add to a week, Edit source
  (Book, Other), Paste an NYT link, Sunday prep (no plan, writing, ready and stale, failed), cook card, rating (new,
  already rated), Settings (server, notifications with permission off, Loblaws), Staples, share confirm (NYT link,
  pages from a book, pages from an other source), camera, first-run setup.
- Three dialogs with a text field (Edit source, Paste link, Swap) never go idle in Robolectric, so they were drawn in
  place with the same contents in an M3 dialog frame; the entry dialog is private, so a copy of its code was
  rendered. Menus were drawn open the same way.
- Contrast recomputed (WCAG relative luminance) from the fixed Garden tokens in `ui/theme/Theme.kt`, and checked
  against pixels sampled from the screenshots (the unselected chip border is `outlineVariant`, `#DDD6CB`).
- Code read for touch targets, TalkBack labels, roles, headings, live regions, traversal order, insets, empty, error
  and offline states.
- Frameworks: design critique (hierarchy, consistency, usability), WCAG 2.2 AA checklist (incl. the 2.2 additions:
  2.4.11 focus not obscured, 2.5.7 dragging, 2.5.8 target size, 3.3.7 redundant entry, 3.3.8 accessible
  authentication), UX-copy review with a non-technical second cook in mind.

The harness can't cover: TalkBack speech and gestures, real system bars and the keyboard, notifications, the
platform time picker, the camera, the Loblaws page itself, motion settings. See "Check on a phone" below.

## Contrast (light / dark)

Text and UI pairs, Garden theme:

| Pair | Light | Dark | Needed |
|---|---|---|---|
| Body text (onSurface on surface) | 15.9 | 14.3 | 4.5 |
| Secondary text (onSurfaceVariant on surface) | 7.2 | 10.5 | 4.5 |
| Text in cards (onSurface on surfaceContainerHighest) | 13.1 | 9.8 | 4.5 |
| Secondary text in cards (onSurfaceVariant on surfaceContainerHighest) | 6.0 | 7.2 | 4.5 |
| Text buttons, links, "From:" / "Shopping for:" values (primary on surface) | 5.9 | 10.4 | 4.5 |
| Text buttons in cards (primary on surfaceContainerHighest) | 4.9 | 7.2 | 4.5 |
| Dialog text (onSurfaceVariant on surfaceContainerHigh) | 6.3 | 8.4 | 4.5 |
| Dialog buttons (primary on surfaceContainerHigh) | 5.1 | 8.3 | 4.5 |
| Menus (onSurface on surfaceContainer) | 14.4 | 12.9 | 4.5 |
| Filled buttons (onPrimary on primary) | 6.3 | 7.8 | 4.5 |
| Add recipe button, drop zone (onPrimaryContainer on primaryContainer) | 11.4 | 7.2 | 4.5 |
| Offline banner (onTertiaryContainer on tertiaryContainer) | 13.1 | 7.4 | 4.5 |
| Errors and warnings (error on surface) | 6.1 | 10.4 | 4.5 |
| Errors in cards (error on surfaceContainerHighest) | 5.0 | 7.2 | 4.5 |
| Bottom bar labels (onSurfaceVariant on surfaceContainer) | 6.6 | 9.5 | 4.5 |
| Selected tab label, terracotta (accent text on surfaceContainer) | 5.2 | 9.2 | 4.5 |
| Selected chip label (accent onContainer on accent container) | 12.8 | 9.2 | 4.5 |
| Checkbox, switch, progress (primary on surface) | 5.9 | 10.4 | 3 (UI) |
| Progress on its track (primary on secondaryContainer) | 4.9 | 7.2 | 3 (UI) |
| Field outlines (outline on surface) | 3.8 | 5.7 | 3 (UI) |
| Field outlines in dialogs (outline on surfaceContainerHigh) | 3.3 | 4.5 | 3 (UI) |

State indicators (what tells selected from unselected):

| Pair | Light | Dark | Needed |
|---|---|---|---|
| Selected chip fill vs page (accent container on surface) | 1.2 | 1.5 | 3 |
| Selected chip fill vs dialog (accent container on surfaceContainerHigh) | **1.0** | 1.2 | 3 |
| Unselected chip border vs page (outlineVariant on surface) | 1.4 | 1.9 | 3 |
| Unselected chip border vs dialog (outlineVariant on surfaceContainerHigh) | 1.2 | 1.5 | 3 |
| Selected tab pill vs bar (accent container on surfaceContainer) | 1.1 | 1.3 | 3 |
| Card vs page (surfaceContainerHighest on surface) | 1.2 | 1.5 | n/a (tonal step) |
| List dividers (outlineVariant on surface) | 1.4 | 1.9 | decorative, exempt |

Every text pair passes AA, as `GardenContrastTest` already enforces. The state indicators don't: see finding 1.
`GardenContrastTest` checks "field & chip outlines" with `outline`, but Material's FilterChip and SuggestionChip draw
their border in `outlineVariant`, and the test has no selected-vs-unselected pair.

## Findings

Severity: **Blocker** = fails WCAG 2.2 AA on a core task, or misleads people into the wrong action. **Should fix** =
a real barrier or a clear usability problem. **Polish** = worth doing when the screen is touched anyway.
Effort: S = under an hour, M = a few hours, L = a design-session item.

### Blocker

1. **Selected chips can barely be told apart from unselected ones** (rating 1–5 and company, entry dialog Move to
   and Make, Add to a week night, Book / Other, Recipes sort and Good for company, staples reminder day). The selected
   state is only a pale terracotta fill (1.2:1 against the page, **1.0:1 inside dialogs** in light mode) and the
   unselected border is `outlineVariant` (1.4:1). There's no check mark. In the light entry dialog the selected
   "Tue" and "×1" are almost invisible; in grayscale or with low vision the rating you picked doesn't show.
   Fails 1.4.11 Non-text Contrast and leans on colour (1.4.1). TalkBack does hear the state.
   Fix: pass `leadingIcon = { if (selected) Icon(check) }` to every FilterChip, the standard M3 pattern (one shared
   `GardenChip` wrapper), and give the selected chip a visible edge: a 1 dp border in the accent text colour
   (light `#9C4A30`: 5.7:1 on the page, 5.0:1 in dialogs; dark `#F1B8A0`: 10.2:1 and 8.1:1). Add "selected chip vs page", "selected chip vs dialog" and "chip border" pairs to
   `GardenContrastTest`. Effort S.
2. **Status messages are silent for TalkBack** (every screen). No text uses a live region, so errors and progress
   that appear without focus aren't announced: "Pick 1 to 5 first." on the rating screen, a failed Save in Staples
   or Edit source, "Finding products… 3 of 11", "Writing cook cards: 2 of 5", import cards changing from Reading to
   Added, the offline banner. Fails 4.1.3 Status Messages.
   Fix: `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` on `MessageText`, `OfflineBanner`, the import
   card text and the progress lines (Assertive only for errors after a button press). Effort S.

### Should fix

3. **A load error with no saved copy looks like an empty week** (This week, Sunday prep). With no copy on the phone
   (a new phone, the first launch away from home), This week shows the red "Can't reach the meal-prep server…" and
   right under it "Nothing planned yet — add recipes from your Recipes." with an Add recipes button and
   ○ Planned / ○ Cart sent / ○ Prep done. Sunday prep shows "No prep plan for this week yet" with "Write my prep
   plan". Both invite planning or writing again when nothing is actually known.
   Fix: when the load failed and nothing is saved, show only the message and Try again; no context button, no empty
   state, no status strip (`HomeViewModel.load` sets `action` from an empty list; `PrepContent` treats
   `plan == null` as "no plan"). Effort S.
4. **"All set" is a big filled button that does nothing** (This week, past weeks and weeks with everything done).
   `contextRoute(AllSet)` is null, so the tap goes nowhere, and a full-width primary button suggests the main action.
   Fix: show it as a line of text ("✓ All set for this week"), or not at all. Effort S.
5. **Single-choice chip groups are read as checkboxes** (rating 1–5, company, Move to, Make, Book / Other, sort,
   nights). Material's FilterChip has the checkbox role, so TalkBack says "Tue, checkbox, checked" for what is one
   choice out of eight, and the group isn't announced as a group. Fix: wrap each group in `selectableGroup()` and give
   its chips `Role.RadioButton` (keep Checkbox for the one real toggle, Good for company). Effort S.
6. **Rows that don't wrap break at 200 % text**:
   - Loblaws handoff (failed): "Try again · Copy cart ID · Details" in one row; "Details" breaks to "Detail / s".
   - Sunday prep: "The week changed since this plan was written." squeezed into a column one or two words wide
     beside "Write a new plan".
   - Cook card: the "Timer 20 min" button takes the right half, so the step reads "3. Add / tomatoes / and beans; /
     simmer" (the screen used with wet hands at arm's length).
   - Swap: the search field next to the Search button is so narrow its label wraps inside the field.
   - Recipe detail: "From: Invented / Pantry Book · A. / Writer, p. 112" beside Edit source.
   Fix: FlowRow for button rows; put the stale note, the timer button and Edit source on their own line under the
   text; make Search the keyboard action and a trailing icon. Effort S.
7. **Bottom bar label wraps at 200 % text** *(fixed in 0.8.0)* ("Shopping / list"), as in 0.4 (backlog 7, still open), and the bar
   grows taller. Fix: shorter label ("Shopping" or "List"), or `alwaysShowLabel = false` at large font scales.
   Effort S (renames a tab).
8. **Weeks can only be changed by swiping** *(fixed in 0.8.0)* (This week; backlog 8, still open). Besides discoverability this is now
   an accessibility gap: TalkBack users can only reach other weeks through the pager's scroll actions, and the title
   ("Next week", "Week of Oct 25") gives no dates for this or next week. Fix: ‹ › icon buttons beside the title
   ("Previous week" / "Next week") and the date range under it ("Oct 11 – 17"). Effort M.
9. **Leaving a screen works six ways** *(fixed in 0.8.0)* (backlog 10, still open, now on more screens): Close top right (recipe,
   rating), Done top right (Staples), Done at the very bottom of a long page (Settings), Close top left (Loblaws),
   Cancel at the bottom (share, camera), and nothing visible at all (Cart, Sunday prep, cook card: system back only).
   Fix: one small top app bar on every non-tab screen with a back arrow ("Navigate up") and the title as a heading.
   Effort M.
10. **Recipes at 200 % text: the controls take 40 % of the screen** *(fixed in 0.8.0)* (Recipes). Search, four chips and the source line
    stay pinned above the list, so about one and a half recipes show, and the large Add recipe button covers the
    middle of a row. The button is also last in TalkBack order, after every row in the list.
    Fix: scroll the controls with the list (make them the first LazyColumn items); shrink the button to an icon on
    scroll; `isTraversalGroup` / `traversalIndex` so Add recipe comes right after the header. Effort M.
11. **Settings opens on the server address and token** (Settings). The part nobody should touch after setup comes
    first, and looks more important than the page title (both 24 sp, but "Settings" is regular and "Meal-prep
    server" semibold), and the notifications that people do change are below the fold. "Loblaws" isn't a heading.
    Fix: Notifications first, then Loblaws, then "Server: Connected ✓ · Change" collapsed. One heading level per
    section. Effort S.
12. **Removing has no confirmation or undo** (entry dialog "Remove from week", rating "Remove rating"). One tap on
    the left dialog button takes the dinner off the week. Fix: an Undo snackbar (better than a confirm dialog for a
    quick fix). Effort S.
13. **The selected tab is shown mostly by hue** (bottom bar). Terracotta label (5.2:1) vs grey label (6.6:1), with a
    pill at 1.1:1 against the bar; in grayscale the tabs look alike. The screen title repeats the tab name on two of
    three tabs, which helps. Fix: semibold label on the selected tab, or filled / outlined icon pairs. Effort S.
14. **Main actions are still far from the thumb** *(fixed in 0.8.0)* (backlog 9, still open): the week's context button and recipe
    detail's Add to a week sit at the top, while Build cart, Send to Loblaws and Add a staple are at the bottom.
    Effort M (design session).
15. **Cart lines still lead with the shopping-list name** *(fixed in 0.8.0)* (Cart review; backlog 11, still open). "onion" is the title;
    the product and price, the thing being decided, are the fourth line. At 200 % one line fills half the screen.
    Effort M (design session).

### Polish

16. **Copy.** "Add recipes from Recipes" (backlog 16, still open; suggest "Choose from Recipes"). The tray has three
    names: "Not on a night yet" (week), "No night (tray)" (entry dialog), "No night yet" (Add to a week): pick one.
    "Make ×0.5 ×1 ×1.5 ×2" → "Amount: half, normal, 1½, double". Prep tasks say "~10 min", the header "about 31 min":
    use "about" (TalkBack reads "~" as "tilde"). Settings jargon: "Keep PC id device trust", "Hide in-app browser
    marker", "WireGuard"; each needs a plain first line ("Skip the sign-in code on this phone").
17. **TalkBack wording.** Ratings read "Family 4.5 slash 5" (library, detail, "Rating: 5/5", "rated 5/5"); add a
    content description "4.5 out of 5". The status strip buttons read "white circle Prep done" for a prep that isn't
    done; give them `stateDescription` ("done" / "not yet") and a click label ("Open the prep plan"). Headings
    missing: "Cart", the camera title, "Loblaws", "Not on a night yet".
18. **Week picker rows are about 36 dp tall** without a detail line (Add to a week, offline). They pass WCAG 2.5.8
    (24 px) but not Android's 48 dp; add `heightIn(min = 48.dp)`. Effort S.
19. **Prep tasks done "on the night" show a disabled checkbox** (Sunday prep). It looks like a broken control. Show
    a small "On the night" label or icon instead. Effort S.
20. **Prep warnings use the error colour** ("⚠ Fish Soup is on Thursday: buy the cod on Wednesday"). Advice, not an
    error: use the tertiary container like the offline banner, keep ⚠. Effort S.
21. **Cart building shows an indeterminate bar** although the count is known ("3 of 11"). Use the determinate bar.
    Effort S.
22. **Save stays disabled with no reason nearby** (share confirm, Other without a name; Edit source Other). "Needed"
    is in the Name field's hint far above. Keep Save enabled and, on tap, mark the field with an error, or put
    "Add a name to save" next to the button. Effort S.
23. **Setup fields** have no keyboard type or IME action (server address: Uri, Next; token: Done, with a show/hide
    toggle). Effort S.
24. **Recipe detail**: wrapped ingredient lines don't hang under the bullet; amounts appear as "1 3/4" while the
    shopping list says "1¾". Effort S.
25. **Source menu mixes books and named other sources** A–Z with nothing to tell them apart ("Cards from friends",
    "Invented Pantry Book"). As designed in 0.7.1; consider a secondary line "Book" / "Other". Effort S.
26. **Loblaws banner at 200 % text** takes a third of the screen above the page. Let it collapse to one line
    ("Your cart is in · Help") once ready. Effort S.
27. **Still open from 0.4** *(fixed in 0.8.0, except the spacing scale)*: four equal-weight lines per library row (13), Refresh as a text button at the end of
    the week, prep and card (14), seven empty nights under the empty-week card (15), the bulky page strip with tiny
    ◀ ▶ glyphs on the share and camera screens (17). The 4/8/12/16/24/32 dp spacing scale from the theme tokens
    isn't applied yet.

## 0.4 backlog re-check

| 0.4 item | Status in 0.7.1 |
|---|---|
| 1–6 (import card cap, status strip, night labels, cart −/+ labels, swap rows, Settings title) | Fixed, still fixed |
| 7 Tab label wraps at 200 % | Open (finding 7) |
| 8 Week navigation invisible | Open, now also an accessibility gap (finding 8) |
| 9 Main actions far from the thumb | Open (finding 14) |
| 10 Leaving a screen three ways | Open, now six ways (finding 9) |
| 11 Cart line hierarchy | Open (finding 15) |
| 12 Dialogs at 200 % unverified | Mostly verified: Add to a week, the entry dialog and the Loblaws close confirmation fit and keep their buttons reachable; Edit source, Paste link and Swap were checked as in-place copies only (finding 6 covers Swap). Keyboard still a phone check |
| 13 Library rows, four equal lines | Open (finding 27); the missing page is shown in the library now |
| 14 Refresh text button | Open (finding 27) |
| 15 Empty week lists seven empty nights | Open (finding 27) |
| 16 "Add recipes from Recipes" | Open (finding 16) |
| 17 Page strip bulky | Open (finding 27) |
| 18 No visual identity | Fixed in 0.6.0 (Garden theme: fixed palette, type scale, shapes); spacing scale still to apply |

## What works

- Every text pair passes AA in light and dark, and the fixed palette makes that true on every phone (0.4 could only
  say "representative" under dynamic colour).
- Touch targets: all buttons, chips, icon buttons and whole-row toggles reach 48 dp (rows set `heightIn(48.dp)`, the
  entry chip uses `minimumInteractiveComponentSize`); the only short rows are in the week picker (finding 18).
- Dragging has a tap alternative (the entry dialog's Move to), which is what 2.5.7 asks for.
- Whole-row checkboxes and switches (shopping list, prep tasks, cook-card steps, notification settings) are single
  controls for TalkBack; the time buttons say "Change the thaw reminder time, now 8:00 PM"; timers say "Start a
  20-minute timer"; rating chips say "4 out of 5"; book suggestions read "Title, by Author, 1961".
- Status is never colour-only in content: ✓ / ○ with words, ⚠ on warnings, strikethrough plus "Put back" on removed
  cart lines, ticked steps keep their tick.
- Large text mostly holds: lists and dialogs scroll, the status strip wraps whole steps, the cart's − 1 + Swap
  Remove row still fits at 200 %, and the import cards keep their 35 % cap.
- Empty and offline states say what to do next (Recipes empty, no company favourites yet, no prep plan, offline
  copy with its time, notifications off with Allow notifications).
- Copy is mostly plain and kind: "Family: was it a hit?", "1 = not again, 5 = a big hit", "Each phone has its own;
  both follow the same household plan", "You can leave this screen — you'll get a notification".
- The cook card keeps the screen on and puts timers in the Clock app, so nothing in the app times out on the cook.
- Remembered book, used-before name chips and prefilled ratings avoid typing things twice (3.3.7).

## Check on a phone

The harness can't show these; do them on the Pixel 9 (and the second phone) with real data:

1. TalkBack through share → Recipes → Add to a week → This week → Shopping list → Cart → Loblaws, then Sunday prep →
   cook card → rating: reading order, chip roles and states, which messages are spoken (finding 2), reaching other
   weeks (finding 8), reaching Add recipe in a long list (finding 10).
2. Font size and Display size both at maximum together (the harness only scaled the font): the bottom bar, the
   entry and Edit source dialogs, the Settings switch rows.
3. The keyboard over dialogs and forms: Edit source (Book and Other), Paste link, Swap, Staples, the rating note;
   check nothing focused is hidden behind the keyboard or the gesture bar (2.4.11).
4. Notifications at large font: thaw, how was dinner, tonight, cart ready; that each tap lands on the right screen.
5. The platform time picker with the Garden theme, in light and dark, and with TalkBack.
6. Settings → Accessibility → Remove animations: the pager, dialogs and the drag still behave.
7. Grayscale (Developer options → Simulate color space → Monochromacy): selected chips and the selected tab
   (findings 1 and 13).
8. The Loblaws sign-in in the WebView: password manager / autofill offered for PC id (3.3.8 Accessible
   Authentication), and the page's own text at 200 %.
9. Accessibility Scanner on every screen, and the Play pre-launch accessibility report for this build.
10. The real Swap dialog and dropdown menus (drawn as copies here).

## Next steps

1. Quick fixes as one release: findings 1–6, 11–13, 16–24 (mostly S).
2. Design session for findings 7–10, 14, 15 and the rest of 27 (one top bar, week navigation, main actions at the
   bottom, cart line layout), then the phone checks above.
3. Keep the checks in the suite: chip state pairs in `GardenContrastTest`, a Compose test that `MessageText` and
   `OfflineBanner` are live regions, and one that single-choice chip groups expose radio-button roles.

## Fixed in 0.7.2 (versionCode 15, 2026-10-06)

Findings 1, 2, 3, 4, 5, 6, 11, 12 (Undo snackbar for Remove from week; a confirm dialog for Remove rating, which
closes the screen), 13 (bold selected tab label), 16, 17, 18, 19, 20, 21, 22, 23, 24, and the checks from next steps 3
(`GardenContrastTest` chip state pairs, `A11yTest` live regions and radio groups). Still open: 7–10, 14, 15, 25, 26, 27
(design session), and the phone checks above.

## Fixed in 0.8.0 (versionCode 16, 2026-10-06)

The design session's picks (options rendered in a throwaway harness, then chosen): findings 7, 8, 9, 10, 14, 15 and
27.

- **7, tab label:** the bottom-bar tab is "Shopping" (one line at 200 %); the screen title still says "Shopping list".
- **8, week navigation:** "Previous week" / "Next week" icon buttons either side of the title, the dates under it
  ("Oct 11 – 17", read as "Oct 11 to 17"); swiping still works. The buttons are disabled at either end of the pager.
- **9, leaving a screen:** one top bar on every screen that isn't a tab (recipe, rating, Staples, Settings, Cart,
  Sunday prep, cook card, Loblaws, share, camera): Back ("Navigate up", 48 dp), the title as a heading that may wrap
  to two lines, and a subtitle where it helps (the cart's and prep's week, the cook card's date, what Back leaves
  behind on Loblaws). Close, Done, the Loblaws Close and the share/camera Cancel are gone. Loblaws still asks "Still
  loading your cart. Close anyway?" on Back while it works.
- **10, Recipes controls:** only the search field stays pinned; a Filters button in it opens the sort chips, Good
  for company and From: under it, and when folded one line says what's in effect ("Newest first · all sources").
  Add recipe comes right after the header in TalkBack order (traversal groups: header 0, Add recipe 1, list 2).
- **14, main actions:** a screen's main action is a full-width button docked at the bottom: This week's next step
  (Build cart, Review cart, Start Sunday prep, Tonight), recipe detail's Add to a week, Shopping's Build cart, Cart's
  total and Send to Loblaws, Staples' Add a staple. Add recipe stays the only floating button.
- **15, cart lines:** a compact row per line: product · size and the price on one line, one quiet line (list name,
  amount, the recipe or "3 recipes"), 48 dp −/+ buttons (the quantity is announced when it changes) and ⋮ with the
  planner's why, the recipes, where the product came from, Swap product and Remove from cart. A line with no product
  says "No product found for …" and offers Search Loblaws directly; a removed line is struck through with Put back.
- **27, the rest of the 0.4 backlog:** library rows are a medium-weight title and one quiet line ("★ 4 · good for
  company · Invented Pantry Book, p. 88", read as "Family 4 out of 5, …"), with "On the plan: …" and "⚠ Page 191
  missing" only when they apply. An empty week shows only its status line and the Nothing planned yet card (no tray,
  no seven empty nights, no drag hint); the week's Refresh only shows for a saved copy or an error (the week reloads on
  return, on swipe and after every change). The page strip on the share and camera screens has a small × per page
  (with an Undo snackbar), 48 dp ‹ › buttons with the page number between them, and on the camera tapping a picture
  retakes it; the share hint says "use ‹ ›". Still open from 27: the 4/8/12/16/24/32 dp spacing scale.

Checks in the suite: `BarsTest` (every pushed screen has a 48 dp Navigate up and its title as a heading, no Close /
Done / Cancel; a long title wraps to two lines), week header, docked action, empty week, filter summary, row wording
and TalkBack text, cart rows and menu, page strip Undo and retake. Before/after screenshots (light, font scale 1.0 and
2.0) were rendered with the throwaway harness and kept out of the repo. Still open: 25, 26, the spacing scale, and
the phone checks above (TalkBack through the new top bar, week buttons and cart menu; the snackbars over the docked
buttons).
