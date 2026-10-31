// Rating a dinner, as on the Android rating screen: family 1–5, "Would you make it for company?" (optional, choosing
// the same answer again clears it), a note, the recipe's ratings "So far" and its earlier notes. And the "How was …?"
// banner for the newest dinner not rated yet, with Later (hidden on this browser until tomorrow).
import { useEffect, useState, type ReactNode } from "react";
import { usePendingRatings, useRate, useRateRecipe, useRecipe } from "../api/queries";
import type { PendingRating, PlanEntry, Rating, RatingSummary, Recipe, RecipeDetail } from "../api/types";
import { formatAvg } from "../lib/format";
import { DAYS_LONG, parseISO, shortDate, toISO } from "../lib/weeks";
import { Button, ErrorNote, Loading, Modal, StarsInput } from "./ui";

export const COMPANY = [["yes", "Yes"], ["maybe", "Maybe"], ["no", "No"]] as const;
type Company = (typeof COMPANY)[number][0];

/** "How was Tuesday's Chili?" (or without the night when it has none). */
export function ratingQuestion(title: string, day: number | null | undefined): string {
  return day != null ? `How was ${DAYS_LONG[day]}'s ${title}?` : `How was ${title}?`;
}

/** "Family 4.5/5 · Company: yes · “less salt”", or null before the first rating. */
export function summaryText(r: RatingSummary | undefined): string | null {
  if (!r || r.times_rated === 0) return null;
  return [r.avg_family != null ? `Family ${formatAvg(r.avg_family)}/5` : null, r.company ? `Company: ${r.company}` : null,
    r.notes[0]?.note ? `“${r.notes[0].note}”` : null].filter(Boolean).join(" · ");
}

/** What the family score, company answer and note dialogs share. */
interface Fields { stars: number; company: Company | null; note: string }

function useFields(): [Fields, (f: Partial<Fields>) => void] {
  const [f, setF] = useState<Fields>({ stars: 0, company: null, note: "" });
  return [f, (p) => setF((x) => ({ ...x, ...p }))];
}

function fieldsOf(r: Rating | null | undefined): Fields {
  return { stars: r?.family ?? 0, company: (r?.company as Company | null | undefined) ?? null, note: r?.note ?? "" };
}

const toRatingIn = (f: Fields) => ({ family: f.stars, company: f.company, note: f.note.trim() || null });

/** The family score, company answer, note, and the recipe's "So far" + earlier notes. */
function RatingFields({ f, set, ratings, before }: { f: Fields; set: (f: Partial<Fields>) => void; ratings: RatingSummary | undefined;
  before?: ReactNode }) {
  const soFar = summaryText(ratings);
  const notes = ratings?.notes ?? [];
  return (
    <>
      {soFar && <p className="muted small">So far: {soFar}</p>}
      {before}
      <fieldset className="field-set">
        <legend>Family: was it a hit? <small>1 = not again, 5 = a big hit</small></legend>
        <StarsInput value={f.stars} onChange={(stars) => set({ stars })} label="Rating" />
      </fieldset>
      <fieldset className="field-set">
        <legend>Would you make it for company? <small>Optional. Choose your answer again to clear it.</small></legend>
        <div className="seg company" role="radiogroup" aria-label="Would you make it for company?">
          {COMPANY.map(([v, label]) => (
            <button key={v} type="button" role="radio" aria-checked={f.company === v} className={f.company === v ? "on" : ""}
              onClick={() => set({ company: f.company === v ? null : v })}>{label}</button>
          ))}
        </div>
      </fieldset>
      <label className="field">
        <span>Note <small>(optional)</small></span>
        <textarea rows={3} value={f.note} maxLength={500} onChange={(e) => set({ note: e.target.value })}
          placeholder="Too salty, kids loved it, double the sauce next time…" />
        <small>Notes show on the recipe and here the next time it's rated.</small>
      </label>
      {notes.length > 0 && (
        <section aria-labelledby="earlier-notes">
          <h3 className="section-h" id="earlier-notes">Earlier notes</h3>
          <ul className="earlier-notes">
            {notes.map((n, i) => (
              <li key={i}>“{n.note}”{n.date && <span className="muted"> — {shortDate(parseISO(n.date))}</span>}</li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}

export function RateDialog({ entry, onClose }: { entry: PlanEntry | null; onClose: () => void }) {
  const rate = useRate(entry?.week ?? "");
  const recipe = useRecipe(entry?.recipe_id ?? null).data;
  const [f, set] = useFields();
  const [shown, setShown] = useState<number | null>(null);
  if (entry && shown !== entry.id) {
    setShown(entry.id);
    set(fieldsOf(entry.rating));
  }
  useEffect(() => { if (entry) rate.reset(); }, [entry?.id]);   // eslint-disable-line react-hooks/exhaustive-deps
  const close = () => { setShown(null); onClose(); };
  const save = () => entry && rate.mutate({ id: entry.id, rating: toRatingIn(f) }, { onSuccess: close });
  const title = entry?.title ?? recipe?.title ?? "this recipe";
  return (
    <Modal open={!!entry} onClose={close} title={entry ? ratingQuestion(title, entry.day) : ""}
      footer={<>
        {entry?.rating && <Button kind="ghost" onClick={() => rate.mutate({ id: entry.id, rating: null }, { onSuccess: close })}>Remove rating</Button>}
        <span className="spacer" />
        <Button kind="outline" onClick={close}>Cancel</Button>
        <Button kind="primary" onClick={save} disabled={!f.stars || rate.isPending}>{entry?.rating ? "Update rating" : "Save rating"}</Button>
      </>}>
      <RatingFields f={f} set={set} ratings={recipe?.ratings} />
      <ErrorNote error={rate.error} />
    </Modal>
  );
}

/** The time cooked a day stands for in a recipe's detail: the planned night that day (rated first), else the
 * recipe's own rating that day, else nothing yet. */
export function cookedOn(d: RecipeDetail | undefined, day: string): { night: number | null; rating: Rating | null } {
  const nights = (d?.history ?? []).filter((h) => h.date === day);
  const night = nights.find((h) => h.rating) ?? nights[0];
  if (night) return { night: night.day ?? null, rating: night.rating ?? null };
  return { night: null, rating: (d?.rated_history ?? []).find((h) => h.date === day)?.rating ?? null };
}

/** "Your last rating: 4/5 on Oct 12", or null before the first. */
export function lastRatingText(r: RatingSummary | undefined): string | null {
  if (!r?.last_family) return null;
  return `Your last rating: ${r.last_family}/5${r.last_rated_at ? ` on ${shortDate(new Date(r.last_rated_at))}` : ""}`;
}

/** Rate a recipe from the recipe itself (a card in Recipes, its drawer): the day it was cooked (the dinner of it still
 * waiting for a rating, else today), then the same questions as rating a dinner. A dinner of it on the plan that day
 * takes the rating (the server decides; the dialog says so). */
export function RateRecipeDialog({ recipe, onClose }: { recipe: Recipe | null; onClose: () => void }) {
  const rate = useRateRecipe();
  const detail = useRecipe(recipe?.id ?? null);
  const d = detail.data;
  const pending = usePendingRatings(!!recipe).data;
  const today = toISO(new Date());
  const [f, set] = useFields();
  const [day, setDay] = useState(today);
  const [shown, setShown] = useState<string | null>(null);
  // once the detail and the waiting dinners are in: start on the waiting dinner's day (or today), filled in if rated
  const ready = !!recipe && !!d && pending !== undefined;
  const key = recipe ? `${recipe.id}` : null;
  if (ready && shown !== key) {
    const start = pending!.find((p) => p.recipe_id === recipe!.id)?.date ?? today;
    setShown(key);
    setDay(start);
    set(fieldsOf(cookedOn(d, start).rating));
  }
  useEffect(() => { if (recipe) rate.reset(); }, [recipe?.id]);   // eslint-disable-line react-hooks/exhaustive-deps
  const at = cookedOn(d, day);
  const pick = (v: string) => {
    if (!v || v > today) return;
    setDay(v);
    const was = cookedOn(d, v).rating;
    if (was) set(fieldsOf(was));
  };
  const close = () => { setShown(null); onClose(); };
  const save = () => recipe && rate.mutate({ id: recipe.id, day, rating: toRatingIn(f) }, { onSuccess: close });
  const last = lastRatingText(d?.ratings ?? recipe?.ratings);
  return (
    <Modal open={!!recipe} onClose={close} title={recipe ? `Rate ${recipe.title}` : ""}
      footer={<>
        {at.rating && <Button kind="ghost" onClick={() => recipe && rate.mutate({ id: recipe.id, day, rating: null }, { onSuccess: close })}>Remove rating</Button>}
        <span className="spacer" />
        <Button kind="outline" onClick={close}>Cancel</Button>
        <Button kind="primary" onClick={save} disabled={!ready || !f.stars || rate.isPending}>{at.rating ? "Update rating" : "Save rating"}</Button>
      </>}>
      {!ready ? (detail.error ? <ErrorNote error={detail.error} /> : <Loading />) : (
        <RatingFields f={f} set={set} ratings={d?.ratings} before={<>
          {last && <p className="muted small">{last}</p>}
          <label className="field cooked-on">
            <span>Cooked on</span>
            <input type="date" value={day} max={today} required onChange={(e) => pick(e.target.value)} />
            <small>{at.night != null ? `This rates ${DAYS_LONG[at.night]}'s dinner on the plan.`
              : at.rating ? "Already rated for this day: saving changes that rating." : day === today ? "Today." : shortDate(parseISO(day)) + "."}</small>
          </label>
        </>} />
      )}
      <ErrorNote error={rate.error} />
    </Modal>
  );
}

const LATER = "mealprep.rateLater";

function laterKeys(): string[] {
  try {
    return JSON.parse(localStorage.getItem(LATER) ?? "[]");
  } catch {
    return [];
  }
}

/** The newest dinner not rated yet unless Later was chosen for it today, and the Later action. */
export function usePendingBanner(enabled: boolean): [PendingRating | null, () => void] {
  const pending = usePendingRatings(enabled).data ?? [];
  const today = toISO(new Date());
  const [later, setLater] = useState(laterKeys);
  const first = pending.find((p) => !later.includes(`${p.entry_id}:${today}`)) ?? null;
  const dismiss = () => {
    if (!first) return;
    // only today's keys are kept: tomorrow the dinner asks again
    const keys = [...later.filter((k) => k.endsWith(`:${today}`)), `${first.entry_id}:${today}`];
    localStorage.setItem(LATER, JSON.stringify(keys));
    setLater(keys);
  };
  return [first, dismiss];
}

/** "How was Tuesday's Chili?" with Rate it and Later. */
export function RatingBanner({ pending, onRate, onLater }: { pending: PendingRating; onRate: () => void; onLater: () => void }) {
  return (
    <section className="rate-banner" aria-label="Rate a dinner">
      <div className="rate-banner-text">
        <b>{ratingQuestion(pending.title, pending.day)}</b>
        <small>{shortDate(parseISO(pending.date))} · a quick rating helps pick family favourites.</small>
      </div>
      <div className="rate-banner-actions">
        <Button kind="primary" size="sm" icon="star" onClick={onRate}>Rate it</Button>
        <Button kind="ghost" size="sm" onClick={onLater}>Later</Button>
      </div>
    </section>
  );
}

/** The plan entry a pending rating stands for (the dialog takes plan entries). */
export function pendingEntry(p: PendingRating): PlanEntry {
  return { id: p.entry_id, week: p.week, recipe_id: p.recipe_id, day: p.day, multiplier: p.multiplier, rating: null, title: p.title };
}
