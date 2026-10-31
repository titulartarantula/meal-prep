// An in-memory stand-in for the API under /api, for `npm run dev:mock` and the e2e tests: the screens can be built
// and tested without the live server or its database. It follows the real routes' shapes, not their rules.
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Plugin } from "vite";
import { BOOKS, BUILD, MEMBERS, NIGHT_RATINGS, PLAN, PRODUCTS, RECIPES, photoSvg, productSvg, type MockProduct, type MockRecipe } from "./fixtures.ts";
import { cooked, put, summary, type PR } from "./ratings.ts";

const COOKIE = "mp_mock_session";
const AS = "mp_mock_member";   // sign in as another member of the invented household (e2e)

const PERMS: Record<string, string[]> = {
  owner: ["members.manage", "roles.assign", "household.settings"], approver: ["request.approve", "cart.build", "cart.send"],
  planner: ["plan.edit", "recipe.edit", "fridge.edit", "rate.for_children"],
  contributor: ["recipe.read", "plan.read", "rate.self", "request.create"], child: [],
};

function iso(d: Date) {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
function sunday(d = new Date(), weeks = 0) {
  return iso(new Date(d.getFullYear(), d.getMonth(), d.getDate() - d.getDay() + 7 * weeks));
}

interface Entry { id: number; week: string; recipe_id: number; day: number | null; multiplier: number; ratings: PR[] }
/** A rating made from the recipe (PUT /recipes/{id}/rating) for a day with no dinner of it on the plan. */
interface Rated extends PR { recipe_id: number; cooked_on: string }
interface Line { id: number; item_key: string; name: string; qty: number | null; unit: string | null; recipes: string[];
  product: ReturnType<typeof product> | null; quantity: number | null; source: string; alternatives: unknown[];
  removed: boolean; status: string; staple: boolean; pinned: boolean; note: string | null; why: string | null;
  needs_check: boolean; extra_id: number | null }
interface Draft { id: number; status: string; weeks: string[]; lines: Line[]; pcx_cart_id: string | null;
  created_at: string; sent_at: string | null; built: number; total: number }

function product(p: MockProduct) {
  return { code: p.code, name: p.name, brand: p.brand ?? null, package_size: p.package_size, price: p.price, stock: "OK",
    image: `/api/_mock/product/${p.code}.svg`, unit_price: p.unit_price, unit_price_unit: p.unit_price_unit,
    deal_text: p.deal_text ?? null, weighted: !!p.weighted, price_quantity: p.price_quantity ?? null, sponsored: false,
    interval: p.interval ?? null, min_qty: p.min_qty ?? null };
}
const byCode = (c: string) => PRODUCTS.find((p) => p.code === c)!;

function fresh() {
  let id = 100;
  const entries: Entry[] = [];
  for (const [off, list] of Object.entries(PLAN)) {
    for (const [rid, day, stars] of list) {
      const at = new Date(Date.now() - 3600e3).toISOString();
      entries.push({ id: ++id, week: sunday(new Date(), Number(off)), recipe_id: rid, day, multiplier: 1,
        ratings: stars ? [{ member_id: null, entered_by: null, family: stars, company: null, note: null, rated_at: at }] : [] });
    }
  }
  for (const [rid, member, stars, note, by] of NIGHT_RATINGS) {
    const e = entries.find((x) => x.week === sunday() && x.recipe_id === rid)!;
    e.ratings.push({ member_id: member, entered_by: by ?? member, family: stars, company: null, note: note ?? null,
      rated_at: new Date(Date.now() - 1800e3).toISOString() });
  }
  const sent: Draft = { id: 1, status: "sent", weeks: [sunday()], lines: [], pcx_cart_id: "8f3c2d1e-0000-4000-8000-000000000001",
    created_at: new Date(Date.now() - 6 * 864e5).toISOString(), sent_at: new Date(Date.now() - 6 * 864e5).toISOString(), built: 0, total: 0 };
  const recipes: (MockRecipe & { revision: number; notesText?: string | null })[] = structuredClone(RECIPES).map((r) => ({ ...r, revision: 1 }));
  return { nextId: 500, entries, rated: [] as Rated[], drafts: [sent] as Draft[], imports: new Map<number, number>(), recipes };
}

export function mockApi(): Plugin {
  let db = fresh();
  return {
    name: "mealprep-mock-api",
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        if (!req.url?.startsWith("/api/")) return next();
        try {
          await route(req, res);
        } catch (e) {
          send(res, 500, { detail: String(e) });
        }
      });
    },
  };

  async function route(req: IncomingMessage, res: ServerResponse) {
    const url = new URL(req.url!, "http://x");
    const path = url.pathname.replace(/^\/api/, "");
    const m = req.method!;
    const signedIn = (req.headers.cookie ?? "").includes(`${COOKIE}=1`);
    const me = Number((req.headers.cookie ?? "").match(new RegExp(`${AS}=(\\d+)`))?.[1] ?? 1);
    const self = MEMBERS.find((x) => x.id === me) ?? MEMBERS[0];
    const perms = [...new Set(self.roles.flatMap((r) => PERMS[r] ?? []))].sort();
    const body = m === "GET" ? null : await readBody(req);
    let g: RegExpMatchArray | null;

    if (path === "/_mock/reset") { db = fresh(); return send(res, 204); }
    if ((g = path.match(/^\/_mock\/photo\/(\w+)\.svg$/))) return g[1] === "broken" ? send(res, 404) : svg(res, photoSvg(g[1]));
    if ((g = path.match(/^\/_mock\/product\/(\w+)\.svg$/))) return svg(res, productSvg(byCode(g[1])?.color ?? "#ccc"));
    if (path === "/auth/login") {
      res.writeHead(303, { Location: url.searchParams.get("next") || "/", "Set-Cookie": `${COOKIE}=1; Path=/; HttpOnly; SameSite=Lax` });
      return res.end();
    }
    if (path === "/health") return send(res, 200, { ok: true });
    if (!signedIn) return send(res, 401, { detail: "Not signed in" });
    if (m !== "GET" && req.headers["x-csrf-token"] !== "mock-csrf") return send(res, 403, { detail: "Missing or wrong X-CSRF-Token header." });

    if (path === "/auth/me") {
      return send(res, 200, { via: "cookie", user: { id: me, email: `${self.name.toLowerCase()}@example.com`, name: `${self.name} Rivera`, picture: null },
        households: [{ id: 1, name: "Rivera household", member_id: me }], household: { id: 1, name: "Rivera household", store_id: 1092 },
        member_id: me, roles: self.roles, csrf_token: "mock-csrf", permissions: perms });
    }
    if (path === "/household/members") {
      return send(res, 200, MEMBERS.map((x) => ({ id: x.id, name: x.name, kind: x.kind, birth_year: x.birth_year, signed_in: x.kind === "account",
        email_set: false, joined_at: "2026-10-30T21:15:00Z", roles: x.roles })));
    }
    // a rating's person: yourself, or (member_id, rate.for_children) a child profile
    const rater = (id: number | null | undefined): number | "forbidden" | "unknown" => {
      if (id == null || id === me) return me;
      const x = MEMBERS.find((y) => y.id === id);
      if (!x) return "unknown";
      return x.kind === "child" && perms.includes("rate.for_children") ? id : "forbidden";
    };
    const refuse = (r: number | "forbidden" | "unknown") =>
      r === "unknown" ? send(res, 404, { detail: "member" }) : send(res, 403, { detail: "You can only rate for yourself or a child profile." });
    const nightOf = (e: Entry) => (e.day == null ? null : iso(addDays(e.week, e.day)));
    const pendingFor = (who: number) => {
      const today = iso(new Date()), from = iso(new Date(Date.now() - 14 * 864e5));
      return db.entries.filter((e) => e.day != null && !e.ratings.some((p) => p.member_id === who) &&
          !db.rated.some((x) => x.recipe_id === e.recipe_id && x.cooked_on === nightOf(e) && x.member_id === who))
        .map((e) => ({ e, date: nightOf(e)! })).filter(({ date }) => date < today && date >= from)
        .sort((a, b) => b.date.localeCompare(a.date) || a.e.id - b.e.id);
    };
    const entryOut = (e: Entry) => ({ id: e.id, week: e.week, recipe_id: e.recipe_id, day: e.day, multiplier: e.multiplier,
      title: db.recipes.find((r) => r.id === e.recipe_id)?.title, ...cooked(e.ratings, me) });
    if (path === "/auth/logout") {
      res.writeHead(204, { "Set-Cookie": `${COOKIE}=; Path=/; Max-Age=0` });
      return res.end();
    }
    if (path === "/recipes" && m === "GET") return send(res, 200, db.recipes.map((r) => recipeOut(r, db.entries, db.rated)));
    if ((g = path.match(/^\/recipes\/(\d+)$/)) && m === "GET") {
      const r = db.recipes.find((x) => x.id === Number(g![1]));
      if (!r) return send(res, 404, { detail: "recipe" });
      const history = db.entries.filter((e) => e.recipe_id === r.id && e.day != null).map((e) => ({
        entry_id: e.id, week: e.week, day: e.day, date: iso(addDays(e.week, e.day!)), multiplier: 1, ...cooked(e.ratings, me) }));
      const days = [...new Set(db.rated.filter((x) => x.recipe_id === r.id).map((x) => x.cooked_on))].sort().reverse();
      const rated_history = days.map((d) => ({ date: d, ...cooked(db.rated.filter((x) => x.recipe_id === r.id && x.cooked_on === d), me) }));
      return send(res, 200, { ...recipeOut(r, db.entries, db.rated), history, imported_history: [], rated_history });
    }
    if ((g = path.match(/^\/recipes\/(\d+)\/rating$/))) {
      // as the server: the dinner waiting for a rating (no day sent), a dinner of it on the plan that day, else the
      // recipe's own rating for that day
      const rid = Number(g[1]);
      if (!db.recipes.some((x) => x.id === rid)) return send(res, 404, { detail: "recipe" });
      const today = iso(new Date());
      const b = (body ?? {}) as { family: number; company?: string | null; note?: string | null; cooked_on?: string | null; member_id?: number | null };
      const who = rater(m === "DELETE" ? (url.searchParams.get("member_id") ? Number(url.searchParams.get("member_id")) : null) : b.member_id);
      if (typeof who !== "number") return refuse(who);
      let day = m === "DELETE" ? url.searchParams.get("cooked_on") : b.cooked_on ?? null;
      if (m === "DELETE" && !day) return send(res, 422, { detail: "cooked_on" });
      if (day && day > today) return send(res, 422, { detail: "cooked_on can't be in the future" });
      if (!day) day = pendingFor(who).find(({ e }) => e.recipe_id === rid)?.date ?? today;
      const nights = db.entries.filter((e) => e.recipe_id === rid && nightOf(e) === day);
      const has = (e: Entry) => e.ratings.some((p) => p.member_id === who);
      const night = m === "DELETE" ? nights.find(has) : nights.find((e) => !has(e)) ?? nights[0];
      const own = (x: Rated) => x.recipe_id === rid && x.cooked_on === day && x.member_id === who;
      if (m === "DELETE") {
        if (night) night.ratings = put(night.ratings, { member_id: who, remove: true });
        else db.rated = db.rated.filter((x) => !own(x));
        return send(res, 204);
      }
      const p: PR = { member_id: who, entered_by: me, family: b.family, company: b.company ?? null, note: b.note?.trim() || null,
        rated_at: new Date().toISOString() };
      if (night) {
        night.ratings = put(night.ratings, p);
        return send(res, 200, { cooked_on: day, entry_id: night.id, recipe_rating_id: null });
      }
      db.rated = [...db.rated.filter((x) => !own(x)), { ...p, recipe_id: rid, cooked_on: day }];
      return send(res, 200, { cooked_on: day, entry_id: null, recipe_rating_id: db.rated.length });
    }
    if ((g = path.match(/^\/recipes\/(\d+)$/)) && m === "PATCH") {
      const r = db.recipes.find((x) => x.id === Number(g![1]));
      if (!r) return send(res, 404, { detail: "recipe" });
      const b = body as Record<string, unknown>;
      if (b.expected_revision != null && b.expected_revision !== `r${r.revision}`) {
        return send(res, 409, { detail: "This recipe was changed (on another phone?) since you started editing it." });
      }
      if ("title" in b && !String(b.title ?? "").trim()) return send(res, 422, { detail: "title can't be blank" });
      if (typeof b.title === "string") r.title = b.title;
      if ("servings" in b) r.servings = (b.servings as number | null) ?? 0;
      if (Array.isArray(b.ingredients)) r.ingredients = (b.ingredients as string[]).map((l) => [null, null, l]);
      if (Array.isArray(b.steps)) r.steps = b.steps as string[];
      if ("notes" in b) r.notesText = b.notes as string | null;
      if ("description" in b) r.description = (b.description as string | null) ?? undefined;
      if (b.source_kind === "book" || b.source_kind === "other") {
        r.source_kind = b.source_kind;
        r.source = b.source_kind === "book" ? "photo" : "other";
        r.source_title = (b.source_title as string | null) ?? undefined;
        r.source_ref = (b.source_ref as string | null) ?? undefined;
        r.source_author = (b.source_author as string | null) ?? undefined;
      }
      r.revision++;
      return send(res, 200, recipeOut(r, db.entries, db.rated));
    }
    if (path === "/recipes/sources") {
      const groups = new Map<string, { key: string; kind: string; title: string | null; label: string; count: number; author: string | null; isbn: null }>();
      for (const r of db.recipes) {
        const t = r.source_kind === "nyt" ? null : r.source_title ?? null;
        const key = r.source_kind === "nyt" ? "nyt" : `${r.source_kind}:${(t ?? "").toLowerCase()}`;
        const label = r.source_kind === "nyt" ? "NYT Cooking" : t ?? (r.source_kind === "book" ? "Unknown book" : "Other");
        const g0 = groups.get(key) ?? { key, kind: r.source_kind, title: t, label, count: 0, author: r.source_author ?? null, isbn: null };
        g0.count++;
        groups.set(key, g0);
      }
      return send(res, 200, [...groups.values()]);
    }
    if (path === "/books/search") {
      const words = (url.searchParams.get("q") ?? "").toLowerCase().split(/\s+/).filter(Boolean);
      if (words.join(" ").length < 2) return send(res, 200, []);
      return send(res, 200, BOOKS.filter((b) => words.every((w) => `${b.title} ${b.subtitle ?? ""} ${b.authors.join(" ")}`.toLowerCase().includes(w)))
        .map((b) => ({ ...b, publisher: null, source: "openlibrary" })));
    }
    if (path === "/ratings/pending") {
      return send(res, 200, pendingFor(me)
        .map(({ e, date }) => ({ entry_id: e.id, week: e.week, day: e.day, date, recipe_id: e.recipe_id,
          title: db.recipes.find((r) => r.id === e.recipe_id)?.title ?? "", multiplier: e.multiplier })));
    }
    if (path === "/recipes/share") {
      const text = String((body as { text?: string })?.text ?? "");
      if (!/cooking\.nytimes\.com\/recipes\//.test(text)) return send(res, 422, { detail: "no NYT Cooking link found" });
      const r = db.recipes[8];
      return send(res, 200, { recipe: recipeOut(r, db.entries, db.rated), entry: null, existing: true });
    }
    if (path === "/recipes/import") {
      const dry = !/name="dry_run"\r\n\r\nfalse/.test(String(body));
      if (dry) {
        return send(res, 200, { dry_run: true, format: "jsonld", warnings: [], counts: { new: 2, duplicate: 1, failed: 0 }, items: [
          { key: "0", index: 0, title: "Braised white beans with greens", status: "new", action: "add", reasons: [] },
          { key: "1", index: 1, title: "Spiced carrot soup", status: "new", action: "add", reasons: [] },
          { key: "2", index: 2, title: "Sheet-pan gnocchi with sausage & peppers", status: "duplicate", action: "skip", reasons: ["Already in your library"],
            match: { recipe_id: 1, title: "Sheet-pan gnocchi with sausage & peppers", by: "title" } }] });
      }
      const id = ++db.nextId;
      db.imports.set(id, Date.now());
      return send(res, 202, importJob(id));
    }
    if ((g = path.match(/^\/imports\/(\d+)$/))) return send(res, 200, importJob(Number(g[1])));
    if (path === "/weeks") {
      const from = url.searchParams.get("from") ?? sunday(), n = Number(url.searchParams.get("count") ?? 8);
      return send(res, 200, Array.from({ length: n }, (_, i) => {
        const w = iso(addDays(sunday(new Date(from + "T12:00")), 7 * i));
        const sent = db.drafts.find((d) => d.status === "sent" && d.weeks.includes(w));
        return { week: w, entries: db.entries.filter((e) => e.week === w).length, carted: !!sent, cart_stale: false };
      }));
    }
    if (path === "/cart/default-week") {
      const w = [...new Set(db.entries.map((e) => e.week))].sort().find((w) => w >= sunday() &&
        !db.drafts.some((d) => d.status === "sent" && d.weeks.includes(w)));
      return send(res, 200, { week: w ?? null });
    }
    if ((g = path.match(/^\/weeks\/([\d-]+)$/))) {
      return send(res, 200, db.entries.filter((e) => e.week === g![1])
        .sort((a, b) => (a.day ?? 99) - (b.day ?? 99) || a.id - b.id).map(entryOut));
    }
    if ((g = path.match(/^\/weeks\/([\d-]+)\/entries$/))) {
      const e: Entry = { id: ++db.nextId, week: g[1], recipe_id: (body as { recipe_id: number }).recipe_id, day: null, multiplier: 1, ratings: [] };
      db.entries.push(e);
      return send(res, 200, entryOut(e));
    }
    if ((g = path.match(/^\/weeks\/([\d-]+)\/draft$/))) {
      const d = [...db.drafts].reverse().find((d) => d.weeks.includes(g![1]));
      return d ? send(res, 200, draftOut(d)) : send(res, 404, { detail: "no cart draft" });
    }
    if ((g = path.match(/^\/plan\/(\d+)$/))) {
      const i = db.entries.findIndex((e) => e.id === Number(g![1]));
      if (i < 0) return send(res, 404, { detail: "entry" });
      if (m === "DELETE") db.entries.splice(i, 1);
      else Object.assign(db.entries[i], body);
      return send(res, 204);
    }
    if ((g = path.match(/^\/plan\/(\d+)\/rating$/))) {
      const e = db.entries.find((e) => e.id === Number(g![1]));
      if (!e) return send(res, 404, { detail: "entry" });
      const b = (body ?? {}) as { family: number; company?: string | null; note: string | null; member_id?: number | null };
      const who = rater(m === "DELETE" ? (url.searchParams.get("member_id") ? Number(url.searchParams.get("member_id")) : null) : b.member_id);
      if (typeof who !== "number") return refuse(who);
      e.ratings = put(e.ratings, m === "DELETE" ? { member_id: who, remove: true }
        : { member_id: who, entered_by: me, family: b.family, company: b.company ?? null, note: b.note?.trim() || null, rated_at: new Date().toISOString() });
      return send(res, 204);
    }
    if (path === "/staples") {
      return send(res, 200, [
        { id: 1, name: "2% milk", qty: 1, unit: null, weekly: true, position: 0, last_bought: new Date(Date.now() - 6 * 864e5).toISOString(), created_at: "2026-10-05" },
        { id: 2, name: "Eggs", qty: 1, unit: null, weekly: true, position: 1, last_bought: new Date(Date.now() - 6 * 864e5).toISOString(), created_at: "2026-10-05" },
        { id: 3, name: "Lemonade", qty: 1, unit: null, weekly: false, position: 2, last_bought: null, created_at: "2026-10-05" }]);
    }
    if (path === "/list") {
      const weeks = (body as { weeks: string[] }).weeks;
      const names = new Set(db.entries.filter((e) => weeks.includes(e.week)).map((e) => e.recipe_id));
      const items: unknown[] = [];
      for (const r of db.recipes.filter((r) => names.has(r.id))) {
        for (const [qty, unit, name, prep] of r.ingredients) {
          const onHand = /oil|salt|curry powder|sesame|vinegar|maple|paste|miso/.test(name);
          items.push({ key: name, name: name[0].toUpperCase() + name.slice(1), qty, unit, prep: prep ?? null, likely_on_hand: onHand,
            needed: !onHand, recipes: [r.title], staple: false, staple_packs: null, extra_id: null, fridge: null, fridge_qty: null, fridge_items: [] });
        }
      }
      for (const s of (body as { staples: number[] }).staples ?? []) {
        items.push({ key: s === 1 ? "milk" : "eggs", name: s === 1 ? "2% milk" : "Eggs", qty: null, unit: null, prep: null, likely_on_hand: false,
          needed: true, recipes: ["Staples"], staple: true, staple_packs: 1, extra_id: null, fridge: null, fridge_qty: null, fridge_items: [] });
      }
      return send(res, 200, items);
    }
    if (path === "/drafts" && m === "POST") {
      const weeks = (body as { weeks: string[] }).weeks;
      const d: Draft = { id: ++db.nextId, status: "building", weeks, lines: [], pcx_cart_id: null, created_at: new Date().toISOString(),
        sent_at: null, built: Date.now(), total: BUILD.length };
      db.drafts.push(d);
      return send(res, 202, { id: d.id, status: "building" });
    }
    if ((g = path.match(/^\/drafts\/(\d+)(\/.*)?$/))) {
      const d = db.drafts.find((d) => d.id === Number(g![1]));
      if (!d) return send(res, 404, { detail: "draft" });
      const rest = g[2] ?? "";
      if (!rest) return send(res, 200, draftOut(d));
      let h: RegExpMatchArray | null;
      if (rest === "/send") {
        d.status = "sent";
        d.pcx_cart_id = "c0ffee00-4a1b-4c2d-9e8f-" + String(d.id).padStart(12, "0");
        d.sent_at = new Date().toISOString();
        return send(res, 200, { pcx_cart_id: d.pcx_cart_id });
      }
      if (rest === "/lines" && m === "POST") {
        const b = body as { term: string; product_code: string; quantity: number };
        d.lines.push(line(++db.nextId, b.term, b.term[0].toUpperCase() + b.term.slice(1), null, null, b.product_code, b.quantity || 1, "user", ["Added"], db.nextId));
        return send(res, 201, draftOut(d));
      }
      if ((h = rest.match(/^\/lines\/(\d+)$/))) {
        const l = d.lines.find((l) => l.id === Number(h![1]));
        if (!l) return send(res, 404, { detail: "line" });
        const b = body as { quantity?: number; removed?: boolean; product_code?: string };
        if (b.quantity != null) l.quantity = b.quantity;
        if (b.removed != null) l.removed = b.removed;
        if (b.product_code) { l.product = product(byCode(b.product_code)); l.source = "user"; }
        return send(res, 200, draftOut(d));
      }
      if ((h = rest.match(/^\/lines\/(\d+)\/choices$/))) {
        const l = d.lines.find((l) => l.id === Number(h![1]));
        return send(res, 200, choices(url.searchParams.get("term") || l?.name || "", l?.product?.code));
      }
    }
    if (path === "/products/choices") return send(res, 200, choices(url.searchParams.get("term") ?? ""));
    return send(res, 404, { detail: `mock: no route ${m} ${path}` });
  }

  function draftOut(d: Draft) {
    if (d.status === "building") {
      const done = Math.min(d.total, Math.floor((Date.now() - d.built) / 300));
      if (done >= d.total) {
        d.status = "ready";
        d.lines = BUILD.map(([key, name, qty, unit, code, n, src], i) =>
          line(1000 + i, key, name, qty, unit, code, n, src, key === "milk" || key === "eggs" ? ["Staples"] : recipesFor(db.recipes, key), null));
      } else {
        return { ...base(d), progress: { done, total: d.total } };
      }
    }
    return base(d);
  }

  function base(d: Draft) {
    const est = d.lines.filter((l) => !l.removed && l.product).reduce((s, l) => s + (l.product!.price * (l.quantity ?? 0)) / (l.product!.price_quantity ?? 1), 0);
    return { id: d.id, status: d.status, error: null, progress: { done: d.total, total: d.total }, weeks: d.weeks, lines: d.lines,
      pcx_cart_id: d.pcx_cart_id, estimated_total: Math.round(est * 100) / 100, created_at: d.created_at, sent_at: d.sent_at, stale: false };
  }

  function line(id: number, key: string, name: string, qty: number | null, unit: string | null, code: string, n: number, src: string,
    recipes: string[], extra: number | null): Line {
    const p = byCode(code);
    return { id, item_key: key, name, qty, unit, recipes, product: product(p), quantity: n, source: src, alternatives: [], removed: false,
      status: "matched", staple: recipes.includes("Staples"), pinned: false, extra_id: extra, needs_check: false,
      note: key === "green onion" ? "Need 4 → 1 bunch (about 6)" : null, why: null };
  }

  function choices(term: string, current?: string) {
    const t = term.toLowerCase().split(/\s+/).filter((w) => w.length > 2);
    const hits = PRODUCTS.filter((p) => t.some((w) => p.name.toLowerCase().includes(w.replace(/s$/, ""))));
    const list = (hits.length ? hits : PRODUCTS.slice(0, 6)).map((p) => ({ product: product(p), group: p.code === current ? "current" : p.deal_text ? "deal" : "other",
      current: p.code === current, pinned: false, suggested_quantity: 1, last_bought: null, price_change: null, cover: null, bulk: false }));
    list.sort((a, b) => Number(b.current) - Number(a.current));
    return { term, page: 0, total: list.length, has_more: false, choices: list };
  }

  function importJob(id: number) {
    const started = db.imports.get(id) ?? 0;
    const done = Date.now() - started > 1500;
    return { id, dry_run: false, status: done ? "done" : "running", progress: { done: done ? 2 : 1, total: 2 }, warnings: [],
      counts: done ? { added: 2, skipped: 1 } : { added: 1, pending: 1 }, items: [], error: null, existing: false };
  }
}

function recipesFor(recipes: MockRecipe[], key: string): string[] {
  return recipes.filter((r) => r.ingredients.some(([, , n]) => n.includes(key.split(" ")[0]))).map((r) => r.title).slice(0, 2);
}

function recipeOut(r: MockRecipe & { revision?: number; notesText?: string | null }, entries: Entry[], own: Rated[] = []) {
  // the household's own ratings (every time cooked with one, dinners and days rated from the recipe) on top of the
  // invented history
  const days: [string, PR[]][] = [
    ...entries.filter((e) => e.recipe_id === r.id && e.ratings.length)
      .map((e): [string, PR[]] => [e.day == null ? e.week : iso(addDays(e.week, e.day)), e.ratings]),
    ...[...new Set(own.filter((x) => x.recipe_id === r.id).map((x) => x.cooked_on))]
      .map((d): [string, PR[]] => [d, own.filter((x) => x.recipe_id === r.id && x.cooked_on === d)]),
  ];
  return { id: r.id, title: r.title, source: r.source, source_url: r.source_kind === "nyt" ? `https://cooking.nytimes.com/recipes/${1000 + r.id}` : null,
    servings: r.servings || null, source_kind: r.source_kind, source_title: r.source_title ?? null, source_ref: r.source_ref ?? null,
    source_author: r.source_author ?? null, source_isbn: null, notes: r.notesText ?? null,
    total_minutes: r.minutes, image: r.photo ? `/api/_mock/photo/${r.photo}.svg` : null, description: r.description ?? null,
    ingredients: r.ingredients.map(([qty, unit, name, prep]) => ({ raw: [qty, unit, name].filter(Boolean).join(" "), name, qty, unit, prep: prep ?? null,
      likely_on_hand: false, expanded: false })),
    steps: r.steps, ratings: summary(r, days), planned_weeks: [], schema_extra: {}, revision: `r${r.revision ?? 1}` };
}

function addDays(week: string, n: number) {
  const [y, m, d] = week.split("-").map(Number);
  return new Date(y, m - 1, d + n);
}

function readBody(req: IncomingMessage): Promise<unknown> {
  return new Promise((ok) => {
    const chunks: Buffer[] = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const s = Buffer.concat(chunks).toString();
      try {
        ok(s ? JSON.parse(s) : {});
      } catch {
        ok(s);
      }
    });
  });
}

function send(res: ServerResponse, status: number, data?: unknown) {
  res.statusCode = status;
  if (data === undefined) return res.end();
  res.setHeader("Content-Type", "application/json");
  res.end(JSON.stringify(data));
}

function svg(res: ServerResponse, s: string) {
  res.setHeader("Content-Type", "image/svg+xml");
  res.setHeader("Cache-Control", "max-age=3600");
  res.end(s);
}
