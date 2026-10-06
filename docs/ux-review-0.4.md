# UX review — app 0.4.2

Scope: every main screen of the Android app as of 0.4.2 (library-first recipes, recipe sources). Input for the planned
design session and the full WCAG 2.2 AA review; quick wins are fixed in 0.4.2, the rest is backlog.

## Method

- Screens rendered from the app's own composables with synthetic data in a throwaway Robolectric harness (native
  graphics, window views drawn to bitmaps; not committed) at a Pixel 9-like size (1080 × 2424 px, 420 dpi ≈ 411 × 923 dp),
  font scale 1.0 and 2.0, light and dark: This week (empty, planned, dragging, entry dialog), Recipes (list, search,
  source filter + its menu, Unknown book filter, Add recipe menu, import cards, empty), recipe detail, Add to a week,
  Shopping list (+ week menu), Staples, Cart review, Loblaws handoff (working, ready, failed + details, close
  confirmation), Settings, Share/scan confirm (NYT link, cookbook pages with Which book?), camera.
- Contrast computed (WCAG relative luminance) from the rendered Material 3 scheme. The app uses Android dynamic colour,
  so the hues follow the wallpaper, but Material's tonal roles keep the same tone pairs; the ratios below are
  representative, not exact for every wallpaper.
- Code read for touch targets, TalkBack labels and roles, headings and insets.
- Frameworks: design critique (hierarchy, consistency, usability), WCAG 2.1/2.2 AA checklist, UX-copy review.

Not covered by the harness (check on a phone): real system bars and gesture navigation insets, the keyboard over
dialogs, dialogs at 200 % text (the harness can't scale dialog windows), TalkBack reading order, motion.

## Contrast (light / dark)

| Pair | Light | Dark | Needed |
|---|---|---|---|
| Body text (onSurface on surface) | 16.3 | 14.4 | 4.5 |
| Secondary text (onSurfaceVariant on surface) | 8.9 | 10.9 | 4.5 |
| Text buttons, links (primary on surface) | 6.1 | 10.9 | 4.5 |
| Text buttons inside cards (primary on surfaceContainerHighest) | 5.0 | 7.3 | 4.5 |
| Secondary text inside cards (onSurfaceVariant on surfaceContainerHighest) | 7.3 | 7.3 | 4.5 |
| Errors (error on surface) | 6.2 | 10.9 | 4.5 |
| Filled buttons (onPrimary on primary) | 6.5 | 7.8 | 4.5 |
| Drop-zone hint (onPrimaryContainer on primaryContainer) | 7.3 | 7.3 | 4.5 |
| Offline banner (onSurface on secondaryContainer) | 13.3 | 7.3 | 4.5 |
| Chip and field outlines (outline on surface) | 4.2 | 5.9 | 3 (UI) |
| List dividers (outlineVariant on surface) | 1.6 | 2.0 | decorative, exempt |
| Disabled controls (onSurface 38 %) | 2.4 | 3.1 | exempt |

No contrast failures. The brand green from the design notes (#2E7D32) isn't used yet (dynamic colour); check it
against white (5.1:1) and the surface if it comes back with a fixed theme.

## Findings

Effort: S = under an hour, M = a few hours, L = a design-session item.

### P1

1. **Import cards can push the week or the library off the screen** (This week, Recipes). The cards sat above the
   scrolling list, so three or four finished imports (or one at 200 % text) left little or no room for the list.
   Fix: the card area is capped at about a third of the screen and scrolls on its own. Effort S. **Fixed in 0.4.2.**
2. **Status strip breaks mid-label at 200 % text** (This week): "○" alone on one line, "Prep" / "done" on the next.
   Fix: the three steps wrap as whole units (flow row, no wrapping inside a step). Effort S. **Fixed in 0.4.2.**
3. **Night labels wrap to "Sun / 4" at 200 % text** (This week): fixed 64 dp column. Fix: at least 64 dp, grows with
   the text, never wraps. Effort S. **Fixed in 0.4.2.**
4. **Cart quantity buttons read as "minus", "1", "plus"** with TalkBack (Cart review): no item named, the number has
   no label. Fix: "One fewer onion", "Quantity 1", "One more onion". Effort S. **Fixed in 0.4.2.**

### P2

5. **Swap results are small, unlabelled tap rows** (Cart review → Swap): rows about 40 dp, no button role. Fix: 48 dp
   rows with a button role and "Choose" action label. Effort S. **Fixed in 0.4.2.**
6. **Settings shows the first-run "Continue" button and has no title** (Settings): after Save & test a second button
   competes with Done. Fix: a "Settings" heading; Continue only on first run. Effort S. **Fixed in 0.4.2.**
7. **"Shopping list" tab label wraps to two lines at 200 % text** (bottom bar), making the bar taller and uneven.
   Suggest a shorter label ("Shopping" or "List") or labels only on the selected tab at large font scales. Effort S,
   but it renames a main destination: **backlog (design session).**
8. **Week navigation is invisible** (This week): weeks change only by swiping; the title says "This week" / "Next
   week" without dates. Suggest ‹ › buttons beside the title and the date range ("Oct 4 – 10"). Effort M. **Backlog.**
9. **Main actions are far from the thumb on the week** (This week, recipe detail): Build cart / Add to a week sit at
   the top of a 6.3 in screen, while Send to Loblaws and Build cart (n items) on other screens are at the bottom.
   Suggest one rule: the screen's main action at the bottom (bottom bar or FAB). Effort M. **Backlog.**
10. **Leaving a screen works three ways** (recipe detail "Close", Staples "Done", Cart and Loblaws via system back /
    "Close" in a banner). Suggest a top app bar with a back arrow on every non-tab screen. Effort M. **Backlog.**
11. **Cart lines lead with the shopping-list name, not the product** (Cart review): "onion" is small and bold, the
    product line ("Yellow Onions · 1.36 kg bag $3.99") is larger; the price, the thing being decided, is at the end
    of a long line and wraps at 200 %. Suggest product name + price as the line's title, the need as secondary text.
    Effort M. **Backlog (design session).**
12. **Dialogs at 200 % text are unverified** (week entry dialog with 9 day chips and 4 size chips; Add to a week; Edit
    source). They scroll, but check on the phone that the buttons stay reachable. Effort S (device check). **Backlog.**

### P3

13. **Library rows carry up to four equal-weight lines** (source, rating, "On the plan", missing page). The missing
    page, the one that needs action, looks like the rest. Suggest a chip or icon for it, and source + rating on one
    line. Effort M. **Backlog.**
14. **"Refresh" is a text button at the end of the week** (This week). Suggest pull-to-refresh (the screen already
    reloads on resume). Effort S. **Backlog.**
15. **The empty week still lists seven empty nights** under the empty-state card. Fine as a frame; consider a lighter
    style for empty nights. Effort S. **Backlog.**
16. **Copy: "Add recipes from Recipes"** repeats the word (button on an empty week). Alternatives: "Choose from
    Recipes", "Open Recipes". Kept as agreed for 0.4.2. Effort S. **Backlog (copy pass).**
17. **Page strip on the scan confirm screen is bulky** (three rows per page: thumbnail, ◀ ▶, Delete). The ◀ ▶ glyph
    buttons have proper TalkBack labels. Suggest drag-to-reorder thumbnails with an overflow menu. Effort M.
    **Backlog.**
18. **No visual identity yet**: default Material 3 with dynamic colour, no app-specific type scale, spacing or icon
    language. For the design session. Effort L. **Backlog.**

## What works

- Every text pair passes AA in light and dark; status is never colour-only (✓ / ○ plus words).
- Icon-only buttons have labels (More options, Move … up/down, page Earlier/Later/Retake/Delete); decorative icons
  are hidden from TalkBack; whole-row switches and checkboxes are single 48 dp controls.
- Drag-and-drop has a tap alternative (the entry dialog's Move to).
- Empty, loading, offline and error states exist on every main screen and say what to do next.
- Library-first flow: the share and scan screens ask only what they need (book and page for scans), and the import
  card offers "Add to a week…" right where the result appears.
- The Add recipe button sits in thumb reach, above the bottom bar, and the list scrolls clear of it.

## Next steps

1. Device pass on the Pixel 9: 200 % text and display size, TalkBack through share → Recipes → Add to a week →
   shopping list → cart → Loblaws, gesture-bar insets.
2. Design session (backlog items 7–11, 13, 18), then the full WCAG 2.2 AA review with Accessibility Scanner and the
   Play pre-launch report.
