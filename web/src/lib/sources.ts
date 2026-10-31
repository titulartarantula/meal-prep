// Where a recipe comes from, and "Which book?", as on Android (core/Sources.kt, core/Books.kt): the household's own
// books first (GET /recipes/sources), then the server's book search (GET /books/search). Free text is always fine.
import type { BookHit, Recipe, RecipeSource } from "../api/types";

export const clean = (t: string) => t.trim().replace(/\s+/g, " ");

/** Same title, ignoring case, accents, punctuation and spacing ("Salt, Fat, Acid, Heat" = "salt fat acid heat"). */
export function sameTitle(a?: string | null, b?: string | null): boolean {
  const norm = (s?: string | null) => (s ?? "").normalize("NFKD").replace(/\p{M}+/gu, "").toLowerCase()
    .replace(/&/g, " and ").replace(/[^\p{L}\p{N}]+/gu, " ").trim();
  return norm(a) === norm(b);
}

/** What is sent to the book search for the typed text; too short = null. */
export function bookQuery(text: string): string | null {
  const q = clean(text);
  return q.length >= 2 ? q : null;
}

/** "Julia Child, Simone Beck" (at most three names, ≤ 200 characters). */
export function authors(names: string[]): string | null {
  const out = [...new Set(names.map((n) => n.trim()).filter(Boolean))].slice(0, 3).join(", ").slice(0, 200);
  return out || null;
}

export interface BookSuggestion { title: string; author: string | null; isbn: string | null; subtitle?: string | null; year?: number | null }
/** What the Book field holds; `picked` = a suggestion (or the saved book with its details): no rows shown. */
export interface BookChoice { title: string; author: string | null; isbn: string | null; picked: boolean }

export function savedBook(r: Pick<Recipe, "source_title" | "source_author" | "source_isbn">): BookChoice {
  const t = r.source_title ?? "";
  return { title: t, author: r.source_author ?? null, isbn: r.source_isbn ?? null, picked: !!t && !!(r.source_author || r.source_isbn) };
}

/** The field's text changed: a picked book's author/ISBN stay only while the text still names that book. */
export function typedBook(c: BookChoice, text: string): BookChoice {
  return c.picked && sameTitle(text, c.title) ? { ...c, title: text } : { title: text, author: null, isbn: null, picked: false };
}

export const yourBooks = (sources: RecipeSource[]): BookSuggestion[] =>
  sources.filter((s) => s.kind === "book" && s.title).map((s) => ({ title: s.title!, author: s.author ?? null, isbn: s.isbn ?? null }));

export const foundBooks = (hits: BookHit[]): BookSuggestion[] =>
  hits.map((h) => ({ title: h.title, author: authors(h.authors ?? []), isbn: h.isbn ?? null, subtitle: h.subtitle, year: h.year }));

/** Rows under the field: none while a picked book is in it; else the household's books containing the text (A–Z, the
 *  text itself left out), then the search's books minus the household's own ones already listed with an author. */
export function suggestBooks(yours: BookSuggestion[], found: BookSuggestion[], c: BookChoice, maxYours = 5, maxFound = 6) {
  if (c.picked) return { yours: [], found: [] };
  const t = c.title.trim().toLowerCase();
  const seen = new Set<string>();
  const mine = yours.filter((b) => { const k = b.title.toLowerCase(); if (seen.has(k)) return false; seen.add(k); return true; })
    .filter((b) => b.title.toLowerCase() !== t && (!t || b.title.toLowerCase().includes(t)))
    .sort((a, b) => a.title.toLowerCase().localeCompare(b.title.toLowerCase())).slice(0, maxYours);
  const known = yours.filter((b) => b.author);
  const others = bookQuery(c.title) == null ? [] : found.filter((f) => !known.some((k) => sameTitle(k.title, f.title))).slice(0, maxFound);
  return { yours: mine, found: others };
}

/** The book to save: a typed title that is one of the household's books (any case) takes that book's spelling,
 *  author and ISBN. */
export function resolveBook(c: BookChoice, yours: BookSuggestion[]): { title: string | null; author: string | null; isbn: string | null } {
  const title = clean(c.title);
  if (!title) return { title: null, author: null, isbn: null };
  if (c.author || c.isbn) return { title, author: c.author, isbn: c.isbn };
  const mine = yours.find((b) => b.title.toLowerCase() === title.toLowerCase() && (b.author || b.isbn));
  return mine ? { title: mine.title, author: mine.author, isbn: mine.isbn } : { title, author: null, isbn: null };
}

/** The household's named other sources ("Mum's recipes"), A–Z. */
export function otherNames(sources: RecipeSource[]): string[] {
  const seen = new Set<string>();
  return sources.filter((s) => s.kind === "other" && s.title).map((s) => clean(s.title!))
    .filter((n) => { const k = n.toLowerCase(); if (seen.has(k)) return false; seen.add(k); return true; })
    .sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase()));
}

/** Names to offer under the Name field: those containing what is typed (all when empty), the typed one left out. */
export function suggestNames(names: string[], typed: string, max = 8): string[] {
  const t = clean(typed).toLowerCase();
  return names.filter((n) => n.toLowerCase() !== t && n.toLowerCase().includes(t)).slice(0, max);
}

/** A household name typed in another case takes that spelling; blank = null (just "Other"). */
export function resolveName(name: string, names: string[]): string | null {
  const n = clean(name);
  if (!n) return null;
  return names.find((x) => x.toLowerCase() === n.toLowerCase()) ?? n;
}

/** "p. 123", "pp. 12–13"; anything else as typed. */
export function page(ref?: string | null): string | null {
  const t = ref?.trim();
  if (!t) return null;
  if (/^\d+$/.test(t)) return `p. ${t}`;
  if (/^\d+\s*[-–,&]\s*\d+/.test(t)) return "pp. " + t.replace(/\s*[-–]\s*/g, "–");
  return t;
}

/** "NYT Cooking", "Salt Fat Acid Heat · Samin Nosrat, p. 123", "Unknown book", "Mum's recipes, the blue binder". */
export function sourceLabel(r: Pick<Recipe, "source_kind" | "source_title" | "source_ref" | "source_author">): string {
  if (r.source_kind === "nyt") return "NYT Cooking";
  if (r.source_kind === "book") {
    const a = r.source_author?.trim();
    const short = a && (a.length <= 30 || !a.includes(",") ? a : a.split(",")[0].trim() + " et al.");
    const book = r.source_title ? [r.source_title, short].filter(Boolean).join(" · ") : "Unknown book";
    return [book, page(r.source_ref)].filter(Boolean).join(", ");
  }
  return [r.source_title || "Other", r.source_ref?.trim() || null].filter(Boolean).join(", ");
}
