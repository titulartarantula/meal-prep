// Per-person ratings for the mock API, with the server's rules (server/mealprep/db.py): one rating per time cooked and
// person (member_id null = "Family": old ratings from before profiles that were never reattributed — the server keeps
// showing them), each person's latest + average, and the family average over everyone's ratings. Good for company is
// not part of a rating: it is one household verdict per recipe (Verdict), set by an Owner.
import { MEMBERS, type MockRecipe } from "./fixtures.ts";

export interface PR { member_id: number | null; entered_by: number | null; family: number; note: string | null; rated_at: string }

/** A recipe's Good for company verdict (no entry = not decided). */
export interface Verdict { company: "yes" | "maybe" | "no"; set_by: number | null; set_at: string }

const order = (id: number | null) => (id == null ? 100 : MEMBERS.findIndex((m) => m.id === id));

export function who(id: number | null): { name: string; kind: string } {
  if (id == null) return { name: "Family", kind: "family" };
  const m = MEMBERS.find((x) => x.id === id);
  return m ? { name: m.name, kind: m.kind } : { name: "Former member", kind: "former" };
}

export function avg(xs: number[]): number | null {
  return xs.length ? Math.round((xs.reduce((a, b) => a + b, 0) / xs.length) * 10) / 10 : null;
}

/** The summary's verdict fields. */
export function verdictOut(v: Verdict | undefined) {
  return { company: v?.company ?? null, good_for_company: v?.company === "yes", company_set_by: v?.set_by ?? null,
    company_set_by_name: v?.set_by != null ? who(v.set_by).name : null, company_set_at: v?.set_at ?? null };
}

export function personRating(p: PR) {
  const w = who(p.member_id);
  const other = p.entered_by != null && p.entered_by !== p.member_id;
  return { member_id: p.member_id, name: w.name, kind: w.kind, family: p.family, note: p.note,
    rated_at: p.rated_at, entered_by: other ? p.entered_by : null, entered_by_name: other ? who(p.entered_by).name : null };
}

/** The CookedRatings fields of one time cooked, for the member asking: `rating` = `my_rating` = their own, whose
 * company is the recipe's verdict. */
export function cooked(rows: PR[], me: number | null, company: string | null = null) {
  const mine = rows.find((r) => r.member_id === me);
  const plain = mine ? { family: mine.family, company, note: mine.note, rated_at: mine.rated_at } : null;
  return { rating: plain, my_rating: plain,
    ratings: [...rows].sort((a, b) => order(a.member_id) - order(b.member_id)).map(personRating),
    rating_avg: avg(rows.map((r) => r.family)), rating_count: rows.length };
}

/** Upsert or remove one person's rating in a list. */
export function put(rows: PR[], p: PR | { member_id: number | null; remove: true }): PR[] {
  const rest = rows.filter((r) => r.member_id !== p.member_id);
  return "remove" in p ? rest : [...rest, p];
}

const BASE_AT = "2026-09-01T18:00:00Z";

/** A recipe's rating summary: the invented history (fixture raters, or the Family's average) plus the live ratings
 * (days: [day cooked, its ratings] — every time cooked with at least one rating). */
export function summary(r: MockRecipe, days: [string, PR[]][], v?: Verdict) {
  const base = r.raters ?? (r.avg ? { family: [Math.round(r.avg), r.avg, r.cooked] as const } : {});
  type Acc = { member_id: number | null; sum: number; count: number; latest: { family: number; note: string | null;
    date: string | null; rated_at: string } };
  const per = new Map<number | null, Acc>();
  const baseDate = new Date(Date.now() - 30 * 864e5).toISOString().slice(0, 10);
  for (const [k, v] of Object.entries(base)) {
    const [latest, a, n, , note] = v as readonly [number, number, number, string?, string?];
    const id = k === "family" ? null : Number(k);
    per.set(id, { member_id: id, sum: a * n, count: n,
      latest: { family: latest, note: note ?? null, date: baseDate, rated_at: BASE_AT } });
  }
  const live = days.flatMap(([d, rows]) => rows.map((p) => ({ d, p }))).sort((a, b) => a.p.rated_at.localeCompare(b.p.rated_at));
  for (const { d, p } of live) {
    const acc = per.get(p.member_id) ?? { member_id: p.member_id, sum: 0, count: 0, latest: null! };
    acc.sum += p.family;
    acc.count++;
    acc.latest = { family: p.family, note: p.note, date: d, rated_at: p.rated_at };
    per.set(p.member_id, acc);
  }
  const all = [...per.values()];
  const count = all.reduce((t, x) => t + x.count, 0);
  const newestLive = live[live.length - 1]?.p;
  const famBase = base.family as readonly [number, number, number, string?] | undefined;
  const notes = [...live.filter((x) => x.p.note).reverse().map((x) => ({ note: x.p.note!, date: x.d, rated_at: x.p.rated_at,
    member_id: x.p.member_id, name: who(x.p.member_id).name })),
  ...(r.notes ?? []).map((note, i) => ({ note, date: new Date(Date.now() - (30 + 14 * i) * 864e5).toISOString().slice(0, 10),
    rated_at: BASE_AT, member_id: null, name: "Family" }))].slice(0, 5);
  return {
    times_cooked: r.cooked + days.length, times_rated: (r.avg ? r.cooked : 0) + days.length,
    avg_family: count ? Math.round((all.reduce((t, x) => t + x.sum, 0) / count) * 10) / 10 : null,
    last_family: newestLive?.family ?? famBase?.[0] ?? null, last_rated_at: newestLive?.rated_at ?? (count ? BASE_AT : null),
    notes, imported_ratings: 0, rating_count: count,
    people: all.sort((a, b) => order(a.member_id) - order(b.member_id)).map((x) => ({ member_id: x.member_id, ...who(x.member_id),
      ...x.latest, avg: Math.round((x.sum / x.count) * 10) / 10, count: x.count })),
    ...verdictOut(v),
  };
}
