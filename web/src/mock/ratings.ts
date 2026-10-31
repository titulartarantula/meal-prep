// Per-person ratings for the mock API, with the server's rules (server/mealprep/db.py): one rating per time cooked and
// person (member_id null = "Family": ratings from before profiles and from the shared phone token), each person's
// latest + average, the family average over everyone's ratings, and the company verdict (each rater's latest answer:
// more yes than no = yes, more no than yes = no, else maybe).
import { MEMBERS, type MockRecipe } from "./fixtures.ts";

export interface PR { member_id: number | null; entered_by: number | null; family: number; company: string | null;
  note: string | null; rated_at: string }

const order = (id: number | null) => (id == null ? 100 : MEMBERS.findIndex((m) => m.id === id));

export function who(id: number | null): { name: string; kind: string } {
  if (id == null) return { name: "Family", kind: "family" };
  const m = MEMBERS.find((x) => x.id === id);
  return m ? { name: m.name, kind: m.kind } : { name: "Former member", kind: "former" };
}

export function avg(xs: number[]): number | null {
  return xs.length ? Math.round((xs.reduce((a, b) => a + b, 0) / xs.length) * 10) / 10 : null;
}

export function verdict(answers: (string | null | undefined)[]): string | null {
  const a = answers.filter(Boolean);
  if (!a.length) return null;
  const yes = a.filter((x) => x === "yes").length, no = a.filter((x) => x === "no").length;
  return yes > no ? "yes" : no > yes ? "no" : "maybe";
}

export function personRating(p: PR) {
  const w = who(p.member_id);
  const other = p.entered_by != null && p.entered_by !== p.member_id;
  return { member_id: p.member_id, name: w.name, kind: w.kind, family: p.family, company: p.company, note: p.note,
    rated_at: p.rated_at, entered_by: other ? p.entered_by : null, entered_by_name: other ? who(p.entered_by).name : null };
}

const plain = (p: PR | undefined | null) => (p ? { family: p.family, company: p.company, note: p.note, rated_at: p.rated_at } : null);

/** The CookedRatings fields of one time cooked, for the member asking. */
export function cooked(rows: PR[], me: number | null) {
  const newest = [...rows].sort((a, b) => b.rated_at.localeCompare(a.rated_at));
  const mine = rows.find((r) => r.member_id === me);
  return { rating: plain(mine ?? newest[0]), my_rating: plain(mine),
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
export function summary(r: MockRecipe, days: [string, PR[]][]) {
  const base = r.raters ?? (r.avg ? { family: [Math.round(r.avg), r.avg, r.cooked, r.company] as const } : {});
  type Acc = { member_id: number | null; sum: number; count: number; latest: { family: number; company: string | null; note: string | null;
    date: string | null; rated_at: string }; answer: string | null };
  const per = new Map<number | null, Acc>();
  const baseDate = new Date(Date.now() - 30 * 864e5).toISOString().slice(0, 10);
  for (const [k, v] of Object.entries(base)) {
    const [latest, a, n, company, note] = v as readonly [number, number, number, string?, string?];
    const id = k === "family" ? null : Number(k);
    per.set(id, { member_id: id, sum: a * n, count: n, answer: company ?? null,
      latest: { family: latest, company: company ?? null, note: note ?? null, date: baseDate, rated_at: BASE_AT } });
  }
  const live = days.flatMap(([d, rows]) => rows.map((p) => ({ d, p }))).sort((a, b) => a.p.rated_at.localeCompare(b.p.rated_at));
  for (const { d, p } of live) {
    const acc = per.get(p.member_id) ?? { member_id: p.member_id, sum: 0, count: 0, answer: null, latest: null! };
    acc.sum += p.family;
    acc.count++;
    acc.latest = { family: p.family, company: p.company, note: p.note, date: d, rated_at: p.rated_at };
    if (p.company) acc.answer = p.company;
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
  const answers = all.map((x) => x.answer);
  const company = verdict(answers);
  return {
    times_cooked: r.cooked + days.length, times_rated: (r.avg ? r.cooked : 0) + days.length,
    avg_family: count ? Math.round((all.reduce((t, x) => t + x.sum, 0) / count) * 10) / 10 : null,
    last_family: newestLive?.family ?? famBase?.[0] ?? null, last_rated_at: newestLive?.rated_at ?? (count ? BASE_AT : null),
    company, notes, imported_ratings: 0, rating_count: count,
    people: all.sort((a, b) => order(a.member_id) - order(b.member_id)).map((x) => ({ member_id: x.member_id, ...who(x.member_id),
      ...x.latest, avg: Math.round((x.sum / x.count) * 10) / 10, count: x.count })),
    company_votes: { yes: answers.filter((a) => a === "yes").length, maybe: answers.filter((a) => a === "maybe").length,
      no: answers.filter((a) => a === "no").length },
    good_for_company: company === "yes",
  };
}
