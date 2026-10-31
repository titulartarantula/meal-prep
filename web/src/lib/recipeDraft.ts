// Edit recipe, as on Android (ui/library/EditRecipe.kt RecipeDraft): the recipe as text fields — the ingredient lines
// as written (an attached sub-recipe's name is a "Name:" heading over its lines; the line it replaced isn't shown),
// one step per line — and the PATCH /recipes/{id} body for only what changed, with the revision the edit began from.
import type { Recipe, RecipePatch } from "../api/types";

export interface RecipeDraft {
  title: string; servings: string; ingredients: string; steps: string; notes: string; description: string;
  prep: string; cook: string; total: string; yieldText: string;
}

export const TITLE_NEEDED = "The recipe needs a title.";

const clean = (s: string) => s.trim().replace(/\s+/g, " ");
const lines = (s: string) => s.split("\n").map(clean).filter(Boolean);
const num = (n: number | null | undefined) => (n == null ? "" : String(n));
const whole = (s: string, min: number, max: number) => !s.trim() || (/^\d+$/.test(s.trim()) && +s >= min && +s <= max);
const intOrNull = (s: string) => (s.trim() ? Number(s.trim()) : null);

/** The ingredient lines as the recipe shows them, grouped by sub-recipe, as written (an unchanged line goes back
 *  exactly as it was and keeps what the server knows about it). */
export function ingredientText(r: Pick<Recipe, "ingredients">): string {
  const out: string[] = [];
  let group: string | null | undefined = undefined;
  for (const i of r.ingredients.filter((i) => !i.expanded)) {
    if ((i.sub_recipe ?? null) !== (group ?? null)) {
      group = i.sub_recipe ?? null;
      if (group) out.push(`${group}:`);
    }
    out.push(clean(i.raw || i.name));
  }
  return out.join("\n");
}

export function fromRecipe(r: Recipe): RecipeDraft {
  return {
    title: r.title, servings: num(r.servings), ingredients: ingredientText(r), steps: r.steps.map(clean).join("\n"),
    notes: r.notes ?? "", description: r.description ?? "", prep: num(r.prep_minutes), cook: num(r.cook_minutes),
    total: num(r.total_minutes), yieldText: r.yield_text ?? "",
  };
}

/** Field → what's wrong with it; empty = it can be saved. */
export function problems(d: RecipeDraft): Partial<Record<keyof RecipeDraft, string>> {
  const out: Partial<Record<keyof RecipeDraft, string>> = {};
  if (!d.title.trim()) out.title = TITLE_NEEDED;
  if (!whole(d.servings, 1, 1000)) out.servings = "Servings: a whole number from 1 to 1000, or empty.";
  for (const k of ["prep", "cook", "total"] as const) if (!whole(d[k], 0, 10_000)) out[k] = "Minutes: a whole number, or empty.";
  return out;
}

/** The PATCH body for what changed (null: nothing did). */
export function changes(d: RecipeDraft, r: Recipe): RecipePatch | null {
  const was = fromRecipe(r);
  const b: RecipePatch = {};
  if (clean(d.title) !== clean(was.title)) b.title = clean(d.title);
  if (d.servings.trim() !== was.servings) b.servings = intOrNull(d.servings);
  if (lines(d.ingredients).join("\n") !== lines(was.ingredients).join("\n")) b.ingredients = lines(d.ingredients);
  if (lines(d.steps).join("\n") !== lines(was.steps).join("\n")) b.steps = lines(d.steps);
  if (d.notes.trim() !== was.notes.trim()) b.notes = d.notes.trim() || null;
  if (d.description.trim() !== was.description.trim()) b.description = d.description.trim() || null;
  if (d.prep.trim() !== was.prep) b.prep_minutes = intOrNull(d.prep);
  if (d.cook.trim() !== was.cook) b.cook_minutes = intOrNull(d.cook);
  if (d.total.trim() !== was.total) b.total_minutes = intOrNull(d.total);
  if (clean(d.yieldText) !== clean(was.yieldText)) b.yield_text = clean(d.yieldText) || null;
  if (Object.keys(b).length === 0) return null;
  if (r.revision) b.expected_revision = r.revision;
  return b;
}

/** Why a save didn't go through: 409 = changed elsewhere since the edit began. */
export function editMessage(status: number | undefined, detail: string): string {
  if (status === 409) return "This recipe was changed (on a phone or another tab?) since you started editing it. " +
    "Copy anything you want to keep, then close, discard and edit again.";
  return detail;
}
