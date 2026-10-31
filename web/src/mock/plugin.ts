// An in-memory stand-in for the API under /api, for `npm run dev:mock` and the e2e tests: the screens can be built
// and tested without the live server or its database. It follows the real routes' shapes, not their rules.
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Plugin } from "vite";
import { BOOKS, BUILD, PLAN, PRODUCTS, RECIPES, photoSvg, productSvg, type MockProduct, type MockRecipe } from "./fixtures.ts";

const COOKIE = "mp_mock_session";

function iso(d: Date) {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
function sunday(d = new Date(), weeks = 0) {
  return iso(new Date(d.getFullYear(), d.getMonth(), d.getDate() - d.getDay() + 7 * weeks));
}

interface Entry { id: number; week: string; recipe_id: number; day: number | null; multiplier: number;
  rating: { family: number; company: string | null; note: string | null; rated_at: string } | null }
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
      entries.push({ id: ++id, week: sunday(new Date(), Number(off)), recipe_id: rid, day, multiplier: 1,
        rating: stars ? { family: stars, company: null, note: null, rated_at: new Date().toISOString() } : null });
    }
  }
  const sent: Draft = { id: 1, status: "sent", weeks: [sunday()], lines: [], pcx_cart_id: "8f3c2d1e-0000-4000-8000-000000000001",
    created_at: new Date(Date.now() - 6 * 864e5).toISOString(), sent_at: new Date(Date.now() - 6 * 864e5).toISOString(), built: 0, total: 0 };
  const recipes: (MockRecipe & { revision: number; notesText?: string | null })[] = structuredClone(RECIPES).map((r) => ({ ...r, revision: 1 }));
  return { nextId: 500, entries, drafts: [sent] as Draft[], imports: new Map<number, number>(), recipes };
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
      return send(res, 200, { via: "cookie", user: { id: 1, email: "sam@example.com", name: "Sam Rivera", picture: null },
        households: [{ id: 1, name: "Rivera household", member_id: 1 }], household: { id: 1, name: "Rivera household", store_id: 1092 },
        member_id: 1, roles: ["owner", "approver", "planner", "contributor"], csrf_token: "mock-csrf",
        permissions: ["cart.build", "cart.send", "household.settings", "members.manage", "plan.edit", "plan.read", "rate.for_children",
          "rate.self", "recipe.edit", "recipe.read", "request.approve", "request.create", "roles.assign", "fridge.edit"] });
    }
    if (path === "/auth/logout") {
      res.writeHead(204, { "Set-Cookie": `${COOKIE}=; Path=/; Max-Age=0` });
      return res.end();
    }
    if (path === "/recipes" && m === "GET") return send(res, 200, db.recipes.map((r) => recipeOut(r, db.entries)));
    if ((g = path.match(/^\/recipes\/(\d+)$/)) && m === "GET") {
      const r = db.recipes.find((x) => x.id === Number(g![1]));
      if (!r) return send(res, 404, { detail: "recipe" });
      const history = db.entries.filter((e) => e.recipe_id === r.id && e.day != null).map((e) => ({
        entry_id: e.id, week: e.week, day: e.day, date: iso(addDays(e.week, e.day!)), multiplier: 1, rating: e.rating }));
      return send(res, 200, { ...recipeOut(r, db.entries), history, imported_history: [] });
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
      return send(res, 200, recipeOut(r, db.entries));
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
      const today = iso(new Date()), from = iso(new Date(Date.now() - 14 * 864e5));
      return send(res, 200, db.entries.filter((e) => e.day != null && !e.rating)
        .map((e) => ({ e, date: iso(addDays(e.week, e.day!)) })).filter(({ date }) => date < today && date >= from)
        .sort((a, b) => b.date.localeCompare(a.date) || a.e.id - b.e.id)
        .map(({ e, date }) => ({ entry_id: e.id, week: e.week, day: e.day, date, recipe_id: e.recipe_id,
          title: db.recipes.find((r) => r.id === e.recipe_id)?.title ?? "", multiplier: e.multiplier })));
    }
    if (path === "/recipes/share") {
      const text = String((body as { text?: string })?.text ?? "");
      if (!/cooking\.nytimes\.com\/recipes\//.test(text)) return send(res, 422, { detail: "no NYT Cooking link found" });
      const r = db.recipes[8];
      return send(res, 200, { recipe: recipeOut(r, db.entries), entry: null, existing: true });
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
        .sort((a, b) => (a.day ?? 99) - (b.day ?? 99) || a.id - b.id)
        .map((e) => ({ ...e, title: db.recipes.find((r) => r.id === e.recipe_id)?.title })));
    }
    if ((g = path.match(/^\/weeks\/([\d-]+)\/entries$/))) {
      const e: Entry = { id: ++db.nextId, week: g[1], recipe_id: (body as { recipe_id: number }).recipe_id, day: null, multiplier: 1, rating: null };
      db.entries.push(e);
      return send(res, 200, { ...e, title: db.recipes.find((r) => r.id === e.recipe_id)?.title });
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
      const b = body as { family: number; company?: string | null; note: string | null };
      e.rating = m === "DELETE" ? null : { family: b.family, company: b.company ?? null, note: b.note ?? null, rated_at: new Date().toISOString() };
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

function recipeOut(r: MockRecipe & { revision?: number; notesText?: string | null }, entries: Entry[]) {
  // the household's own ratings on top of the invented history: newest first
  const rated = entries.filter((e) => e.recipe_id === r.id && e.rating).sort((a, b) => b.rating!.rated_at.localeCompare(a.rating!.rated_at));
  const notes = [...rated.filter((e) => e.rating!.note).map((e) => ({ note: e.rating!.note!, date: e.day == null ? null : iso(addDays(e.week, e.day)),
    rated_at: e.rating!.rated_at })), ...(r.notes ?? []).map((note, i) => ({ note, date: iso(new Date(Date.now() - (30 + 14 * i) * 864e5)), rated_at: "2026-09-01T18:00:00Z" }))].slice(0, 5);
  const company = rated.find((e) => e.rating!.company)?.rating!.company ?? r.company ?? null;
  return { id: r.id, title: r.title, source: r.source, source_url: r.source_kind === "nyt" ? `https://cooking.nytimes.com/recipes/${1000 + r.id}` : null,
    servings: r.servings || null, source_kind: r.source_kind, source_title: r.source_title ?? null, source_ref: r.source_ref ?? null,
    source_author: r.source_author ?? null, source_isbn: null, notes: r.notesText ?? null,
    total_minutes: r.minutes, image: r.photo ? `/api/_mock/photo/${r.photo}.svg` : null, description: r.description ?? null,
    ingredients: r.ingredients.map(([qty, unit, name, prep]) => ({ raw: [qty, unit, name].filter(Boolean).join(" "), name, qty, unit, prep: prep ?? null,
      likely_on_hand: false, expanded: false })),
    steps: r.steps, ratings: { times_cooked: r.cooked, times_rated: r.avg ? r.cooked : 0, avg_family: r.avg, last_family: null,
      last_rated_at: null, company, notes, imported_ratings: 0 }, planned_weeks: [], schema_extra: {}, revision: `r${r.revision ?? 1}` };
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
