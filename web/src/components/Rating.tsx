// Rating a dinner, as on the Android rating screen: a score 1–5, a note, the recipe's ratings "So far" and its earlier
// notes. Since per-person ratings everyone rates for themselves (a parent can rate for a child profile: "Rating for"),
// and the dinner, the recipe card and its drawer show the family average with each person's rating. "Good for
// company?" is not part of a person's rating: it is one household verdict per recipe that an Owner sets (in the rating
// dialog and the recipe drawer; others see it read-only). And the "How was …?" banner for the newest dinner you
// haven't rated yet, with Later (hidden on this browser until tomorrow).
import { useEffect, useState, type ReactNode } from "react";
import { useCan, useMemberId, useMembers, usePendingRatings, useRate, useRateRecipe, useRecipe } from "../api/queries";
import type { Company, PendingRating, PersonRating, PersonSummary, PlanEntry, RatingSummary, Recipe, RecipeDetail } from "../api/types";
import { formatAvg } from "../lib/format";
import { DAYS_LONG, parseISO, shortDate, toISO } from "../lib/weeks";
import { Star } from "./Icon";
import { Button, ErrorNote, Loading, Modal, StarsInput } from "./ui";

export const COMPANY: readonly (readonly [Company, string])[] = [["yes", "Yes"], ["maybe", "Maybe"], ["no", "No"]];

/** "How was Tuesday's Chili?" (or without the night when it has none). */
export function ratingQuestion(title: string, day: number | null | undefined): string {
  return day != null ? `How was ${DAYS_LONG[day]}'s ${title}?` : `How was ${title}?`;
}

/** "Average 4.5/5 from 6 ratings · Company: yes · “less salt”", or null before the first rating. */
export function summaryText(r: RatingSummary | undefined): string | null {
  if (!r || r.times_rated === 0) return null;
  const n = r.rating_count ?? r.times_rated;
  return [r.avg_family != null ? `Average ${formatAvg(r.avg_family)}/5 from ${n} ${n === 1 ? "rating" : "ratings"}` : null,
    r.company ? `Company: ${r.company}` : null, r.notes[0]?.note ? `“${r.notes[0].note}”` : null].filter(Boolean).join(" · ");
}

/** A person's name as shown to the signed-in member (their own row says "you"). */
export function personName(p: { member_id?: number | null; name: string }, me: number | null | undefined): string {
  return me != null && p.member_id === me ? `${p.name} (you)` : p.name;
}

/** The letters on a person's chip: "S", "Fam" for the Family's (shared phone / before profiles) ratings. */
export function initials(p: { name: string; kind: string }): string {
  return p.kind === "family" ? "Fam" : p.kind === "former" ? "?" : p.name.trim().charAt(0).toUpperCase();
}

/** The rating `who` made (null = yourself) among a time cooked's ratings. */
export function ratingOf(rows: PersonRating[] | undefined, who: number | null, me: number | null | undefined): PersonRating | null {
  const id = who ?? me ?? null;
  return (rows ?? []).find((r) => (r.member_id ?? null) === id) ?? null;
}

/** Who the signed-in member can rate for: themselves ("Me", id null) and, with rate.for_children, each child profile. */
export function useRaters(enabled: boolean): { me: number | null; options: { id: number | null; label: string }[] } {
  const me = useMemberId();
  const can = useCan();
  const kids = can("rate.for_children");
  const members = useMembers(enabled && kids).data ?? [];
  return { me, options: [{ id: null, label: "Me" }, ...(kids ? members.filter((m) => m.kind === "child").map((m) => ({ id: m.id, label: m.name })) : [])] };
}

/** "Rating for: Me / Pip" (only when there's a child profile to rate for). */
function RatingFor({ options, who, onChange }: { options: { id: number | null; label: string }[]; who: number | null; onChange: (id: number | null) => void }) {
  if (options.length < 2) return null;
  return (
    <fieldset className="field-set">
      <legend>Rating for</legend>
      <div className="seg rating-for" role="radiogroup" aria-label="Rating for">
        {options.map((o) => (
          <button key={o.id ?? "me"} type="button" role="radio" aria-checked={who === o.id} className={who === o.id ? "on" : ""}
            onClick={() => onChange(o.id)}>{o.label}</button>
        ))}
      </div>
    </fieldset>
  );
}

/** Small read-only stars with the score for screen readers. */
export function MiniStars({ n, size = 12 }: { n: number; size?: number }) {
  return (
    <span className="mini-stars">
      {[1, 2, 3, 4, 5].map((i) => <Star key={i} size={size} filled={i <= n} />)}
      <span className="visually-hidden">{n} of 5</span>
    </span>
  );
}

/** "Good for company: Yes · set by Sam" (or "not decided yet"), for whoever can't change it. */
export function companyText(r: RatingSummary | undefined): string {
  if (!r?.company) return "Good for company: not decided yet";
  const label = COMPANY.find(([v]) => v === r.company)?.[1] ?? r.company;
  return `Good for company: ${label}${r.company_set_by_name ? ` · set by ${r.company_set_by_name}` : ""}`;
}

/** The household's "Good for company?" answer for a recipe (an Owner's choice; choosing it again clears it). */
export function CompanyChoice({ value, onChange, disabled, hint = true }: { value: Company | null; onChange: (c: Company | null) => void;
  disabled?: boolean; hint?: boolean }) {
  return (
    <fieldset className="field-set">
      <legend>Good for company? {hint && <small>The household's answer for this recipe, not part of a rating. Choose it again to clear it.</small>}</legend>
      <div className="seg company" role="radiogroup" aria-label="Good for company?">
        {COMPANY.map(([v, label]) => (
          <button key={v} type="button" role="radio" aria-checked={value === v} className={value === v ? "on" : ""} disabled={disabled}
            onClick={() => onChange(value === v ? null : v)}>{label}</button>
        ))}
      </div>
    </fieldset>
  );
}

/** Each person's rating of one time cooked: name, stars, note (and who entered a child's). */
export function PeopleRatings({ rows, me, label, skip }: { rows: PersonRating[]; me: number | null | undefined; label: string;
  skip?: PersonRating | null }) {
  const shown = rows.filter((r) => r !== skip);
  if (shown.length === 0) return null;
  return (
    <ul className="people-ratings" aria-label={label}>
      {shown.map((r) => (
        <li key={`${r.member_id ?? "family"}`} title={[`${r.name}: ${r.family}/5`, r.note && `“${r.note}”`, r.entered_by_name && `entered by ${r.entered_by_name}`].filter(Boolean).join(" · ")}>
          <span className="who">{personName(r, me)}</span>
          <MiniStars n={r.family} />
          {r.note && <span className="pr-note">“{r.note}”</span>}
          {r.entered_by_name && <small className="muted">entered by {r.entered_by_name}</small>}
        </li>
      ))}
    </ul>
  );
}

/** A recipe's people as small chips: an initial and their latest score ("Fam" for the Family's ratings). */
export function PeopleChips({ people, max = 4 }: { people: PersonSummary[] | undefined; max?: number }) {
  if (!people?.length) return null;
  const more = people.length - max;
  return (
    <span className="people-chips">
      {people.slice(0, max).map((p) => (
        <span key={p.member_id ?? "family"} className={`pchip ${p.kind}`} title={`${p.name}: ${p.family}/5`}>
          <span aria-hidden="true">{initials(p)}</span><b aria-hidden="true">{p.family}</b>
          <span className="visually-hidden">{p.name} {p.family} of 5</span>
        </span>
      ))}
      {more > 0 && <span className="pchip more">+{more}</span>}
    </span>
  );
}

/** What the score and note dialogs share. */
interface Fields { stars: number; note: string }

function useFields(): [Fields, (f: Partial<Fields>) => void] {
  const [f, setF] = useState<Fields>({ stars: 0, note: "" });
  return [f, (p) => setF((x) => ({ ...x, ...p }))];
}

function fieldsOf(r: { family: number; note?: string | null } | null | undefined): Fields {
  return { stars: r?.family ?? 0, note: r?.note ?? "" };
}

/** The rating body; `company` only when an Owner changed the recipe's verdict in the dialog (the server sets it). */
const toRatingIn = (f: Fields, company?: Company | null) =>
  ({ family: f.stars, note: f.note.trim() || null, ...(company !== undefined ? { company } : {}) });

/** The recipe's Good for company verdict in a rating dialog: Owners choose it (undefined = untouched), others read it. */
function useCompanyEdit(ratings: RatingSummary | undefined) {
  const can = useCan()("recipe.company");
  const [picked, setPicked] = useState<Company | null | undefined>(undefined);
  const verdict = (ratings?.company as Company | null | undefined) ?? null;
  const value = picked !== undefined ? picked : verdict;
  return { can, value, set: setPicked, reset: () => setPicked(undefined), change: can && picked !== undefined && picked !== verdict ? picked : undefined,
    ratings };
}
type CompanyEdit = ReturnType<typeof useCompanyEdit>;

/** The score, note, the recipe's Good for company verdict, and its "So far" + earlier notes. */
function RatingFields({ f, set, company, before, whose, me }: { f: Fields; set: (f: Partial<Fields>) => void; company: CompanyEdit;
  before?: ReactNode; whose: string; me: number | null }) {
  const ratings = company.ratings;
  const soFar = summaryText(ratings);
  const notes = ratings?.notes ?? [];
  return (
    <>
      {soFar && <p className="muted small">So far: {soFar}</p>}
      {before}
      <fieldset className="field-set">
        <legend>{whose}: was it a hit? <small>1 = not again, 5 = a big hit</small></legend>
        <StarsInput value={f.stars} onChange={(stars) => set({ stars })} label="Rating" />
      </fieldset>
      {company.can ? <CompanyChoice value={company.value} onChange={company.set} /> : ratings && <p className="muted small company-ro">{companyText(ratings)}</p>}
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
              <li key={i}>“{n.note}”<span className="muted">{[n.name ? ` — ${personName({ member_id: n.member_id, name: n.name }, me)}` : null,
                n.date ? `${n.name ? ", " : " — "}${shortDate(parseISO(n.date))}` : null].filter(Boolean).join("")}</span></li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}

/** "Your" or "Pip's", for the score's question. */
function whoseLabel(options: { id: number | null; label: string }[], who: number | null): string {
  return who == null ? "Your score" : `${options.find((o) => o.id === who)?.label ?? "Their"}'s score`;
}

export function RateDialog({ entry, onClose }: { entry: PlanEntry | null; onClose: () => void }) {
  const rate = useRate(entry?.week ?? "");
  const recipe = useRecipe(entry?.recipe_id ?? null).data;
  const { me, options } = useRaters(!!entry);
  const [f, set] = useFields();
  const company = useCompanyEdit(recipe?.ratings);
  const [who, setWho] = useState<number | null>(null);
  const [shown, setShown] = useState<number | null>(null);
  const target = !entry ? null : who == null ? entry.my_rating ?? null : ratingOf(entry.ratings, who, me);
  if (entry && shown !== entry.id) {
    setShown(entry.id);
    setWho(null);
    set(fieldsOf(entry.my_rating));
    company.reset();
  }
  useEffect(() => { if (entry) rate.reset(); }, [entry?.id]);   // eslint-disable-line react-hooks/exhaustive-deps
  const close = () => { setShown(null); onClose(); };
  const pickWho = (id: number | null) => {
    setWho(id);
    set(fieldsOf(id == null ? entry?.my_rating : ratingOf(entry?.ratings, id, me)));
  };
  const save = () => entry && rate.mutate({ id: entry.id, rating: toRatingIn(f, company.change), member: who }, { onSuccess: close });
  const title = entry?.title ?? recipe?.title ?? "this recipe";
  const others = (entry?.ratings ?? []).filter((r) => (r.member_id ?? null) !== (who ?? me ?? null));
  return (
    <Modal open={!!entry} onClose={close} title={entry ? ratingQuestion(title, entry.day) : ""}
      footer={<>
        {target && <Button kind="ghost" onClick={() => entry && rate.mutate({ id: entry.id, rating: null, member: who }, { onSuccess: close })}>Remove rating</Button>}
        <span className="spacer" />
        <Button kind="outline" onClick={close}>Cancel</Button>
        <Button kind="primary" onClick={save} disabled={!f.stars || rate.isPending}>{target ? "Update rating" : "Save rating"}</Button>
      </>}>
      <RatingFields f={f} set={set} company={company} me={me} whose={whoseLabel(options, who)} before={<>
        <RatingFor options={options} who={who} onChange={pickWho} />
        {others.length > 0 && <>
          <h3 className="section-h" id="night-ratings">This dinner so far</h3>
          <PeopleRatings rows={others} me={me} label="This dinner so far" />
        </>}
      </>} />
      <ErrorNote error={rate.error} />
    </Modal>
  );
}

/** The time cooked a day stands for in a recipe's detail, for one person (who null = yourself): the planned night that
 * day (one they rated first), else the recipe's own ratings that day; their rating of it, and everyone's. */
export function cookedOn(d: RecipeDetail | undefined, day: string, who: number | null = null, me: number | null = null):
  { night: number | null; rating: PersonRating | null; ratings: PersonRating[] } {
  const nights = (d?.history ?? []).filter((h) => h.date === day);
  const night = nights.find((h) => ratingOf(h.ratings, who, me)) ?? nights[0];
  if (night) return { night: night.day ?? null, rating: ratingOf(night.ratings, who, me), ratings: night.ratings ?? [] };
  const rated = (d?.rated_history ?? []).find((h) => h.date === day);
  return { night: null, rating: ratingOf(rated?.ratings, who, me), ratings: rated?.ratings ?? [] };
}

/** "Your last rating: 4/5 on Oct 12" (or "Pip's last rating: …"), or null before their first. */
export function lastRatingText(r: RatingSummary | undefined, who: number | null = null, me: number | null = null,
  name?: string): string | null {
  const id = who ?? me;
  const p = (r?.people ?? []).find((x) => (x.member_id ?? null) === id);
  if (!p) return null;
  return `${who == null ? "Your" : `${name ?? p.name}'s`} last rating: ${p.family}/5 on ${shortDate(new Date(p.rated_at))}`;
}

/** Rate a recipe from the recipe itself (a card in Recipes, its drawer): the day it was cooked (the dinner of it still
 * waiting for your rating, else today), then the same questions as rating a dinner. A dinner of it on the plan that day
 * takes the rating (the server decides; the dialog says so). */
export function RateRecipeDialog({ recipe, onClose }: { recipe: Recipe | null; onClose: () => void }) {
  const rate = useRateRecipe();
  const detail = useRecipe(recipe?.id ?? null);
  const d = detail.data;
  const pending = usePendingRatings(!!recipe).data;
  const { me, options } = useRaters(!!recipe);
  const today = toISO(new Date());
  const [f, set] = useFields();
  const company = useCompanyEdit(d?.ratings);
  const [day, setDay] = useState(today);
  const [who, setWho] = useState<number | null>(null);
  const [shown, setShown] = useState<string | null>(null);
  // once the detail and the waiting dinners are in: start on the waiting dinner's day (or today), filled in if rated
  const ready = !!recipe && !!d && pending !== undefined;
  const key = recipe ? `${recipe.id}` : null;
  if (ready && shown !== key) {
    const start = pending!.find((p) => p.recipe_id === recipe!.id)?.date ?? today;
    setShown(key);
    setDay(start);
    setWho(null);
    set(fieldsOf(cookedOn(d, start, null, me).rating));
    company.reset();
  }
  useEffect(() => { if (recipe) rate.reset(); }, [recipe?.id]);   // eslint-disable-line react-hooks/exhaustive-deps
  const at = cookedOn(d, day, who, me);
  const pick = (v: string) => {
    if (!v || v > today) return;
    setDay(v);
    const was = cookedOn(d, v, who, me).rating;
    if (was) set(fieldsOf(was));
  };
  const pickWho = (id: number | null) => {
    setWho(id);
    set(fieldsOf(cookedOn(d, day, id, me).rating));
  };
  const close = () => { setShown(null); onClose(); };
  const save = () => recipe && rate.mutate({ id: recipe.id, day, rating: toRatingIn(f, company.change), member: who }, { onSuccess: close });
  const whoName = options.find((o) => o.id === who)?.label;
  const last = lastRatingText(d?.ratings ?? recipe?.ratings, who, me, whoName);
  const others = at.ratings.filter((r) => r !== at.rating);
  return (
    <Modal open={!!recipe} onClose={close} title={recipe ? `Rate ${recipe.title}` : ""}
      footer={<>
        {at.rating && <Button kind="ghost" onClick={() => recipe && rate.mutate({ id: recipe.id, day, rating: null, member: who }, { onSuccess: close })}>Remove rating</Button>}
        <span className="spacer" />
        <Button kind="outline" onClick={close}>Cancel</Button>
        <Button kind="primary" onClick={save} disabled={!ready || !f.stars || rate.isPending}>{at.rating ? "Update rating" : "Save rating"}</Button>
      </>}>
      {!ready ? (detail.error ? <ErrorNote error={detail.error} /> : <Loading />) : (
        <RatingFields f={f} set={set} company={company} me={me} whose={whoseLabel(options, who)} before={<>
          <RatingFor options={options} who={who} onChange={pickWho} />
          {last && <p className="muted small">{last}</p>}
          <label className="field cooked-on">
            <span>Cooked on</span>
            <input type="date" value={day} max={today} required onChange={(e) => pick(e.target.value)} />
            <small>{at.night != null ? `This rates ${DAYS_LONG[at.night]}'s dinner on the plan.`
              : at.rating ? "Already rated for this day: saving changes that rating." : day === today ? "Today." : shortDate(parseISO(day)) + "."}</small>
          </label>
          {others.length > 0 && <>
            <h3 className="section-h">That day so far</h3>
            <PeopleRatings rows={others} me={me} label="That day so far" />
          </>}
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

/** The newest dinner you haven't rated yet unless Later was chosen for it today, and the Later action. */
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
  return { id: p.entry_id, week: p.week, recipe_id: p.recipe_id, day: p.day, multiplier: p.multiplier, rating: null, title: p.title,
    my_rating: null, ratings: [], rating_avg: null, rating_count: 0 };
}
