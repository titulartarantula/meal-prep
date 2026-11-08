import { describe, expect, it } from "vitest";
import type { Recipe } from "../api/types";
import { changes, editMessage, fromRecipe, ingredientText, problems } from "./recipeDraft";
import {
  otherNames, page, resolveBook, resolveName, sameTitle, savedBook, sourceLabel, suggestBooks, suggestNames, typedBook,
  webSource,
} from "./sources";

const ing = (raw: string, extra: Partial<Recipe["ingredients"][number]> = {}) =>
  ({ raw, name: raw, likely_on_hand: false, expanded: false, ...extra });

const recipe = (over: Partial<Recipe> = {}): Recipe => ({
  id: 1, title: "Test Stew", source: "photo", servings: 4, steps: ["Brown the beef.", "Simmer  2 hours."],
  ingredients: [ing("1 lb beef"), ing("Batter, page 9", { expanded: true }), ing("1 cup flour", { sub_recipe: "Batter" }),
    ing("2 eggs", { sub_recipe: "Batter" }), ing("Salt")],
  schema_extra: {}, planned_weeks: [], revision: "rev1", source_kind: "book", source_title: "Invented Pantry Book",
  ratings: { times_cooked: 0, times_rated: 0, notes: [], imported_ratings: 0 }, ...over,
} as Recipe);

describe("edit recipe draft", () => {
  it("shows sub-recipes as headings over their lines and leaves out the line they replaced", () => {
    expect(ingredientText(recipe())).toBe("1 lb beef\nBatter:\n1 cup flour\n2 eggs\nSalt");
  });

  it("sends nothing when nothing changed, whitespace included", () => {
    const d = fromRecipe(recipe());
    expect(changes({ ...d, steps: d.steps + "\n\n", title: " Test  Stew " }, recipe())).toBeNull();
  });

  it("sends only what changed, with the revision it began from", () => {
    const d = fromRecipe(recipe());
    expect(changes({ ...d, title: "Test Renamed Stew", steps: "Brown.\n\nSimmer.", servings: "" }, recipe())).toEqual({
      title: "Test Renamed Stew", steps: ["Brown.", "Simmer."], servings: null, expected_revision: "rev1",
    });
    expect(changes({ ...d, ingredients: d.ingredients + "\n3 carrots", notes: "Good cold." }, recipe())).toEqual({
      ingredients: ["1 lb beef", "Batter:", "1 cup flour", "2 eggs", "Salt", "3 carrots"], notes: "Good cold.", expected_revision: "rev1",
    });
  });

  it("checks the title and the numbers", () => {
    const d = fromRecipe(recipe());
    expect(problems(d)).toEqual({});
    expect(Object.keys(problems({ ...d, title: " ", servings: "0", prep: "ten", total: "5" }))).toEqual(["title", "servings", "prep"]);
  });

  it("says what a 409 means", () => {
    expect(editMessage(409, "x")).toMatch(/changed .* since you started editing/);
    expect(editMessage(422, "title can't be blank")).toBe("title can't be blank");
  });
});

describe("sources and books", () => {
  const sources = [
    { key: "book:invented pantry book", kind: "book" as const, title: "Invented Pantry Book", label: "", count: 2, author: "Ada Example", isbn: null },
    { key: "book:big pot suppers", kind: "book" as const, title: "Big Pot Suppers", label: "", count: 1 },
    { key: "other:grandma's cards", kind: "other" as const, title: "Grandma's cards", label: "", count: 1 },
    { key: "other:", kind: "other" as const, title: null, label: "Other", count: 1 },
  ];
  const yours = sources.filter((s) => s.kind === "book").map((s) => ({ title: s.title!, author: s.author ?? null, isbn: s.isbn ?? null }));

  it("compares titles loosely", () => expect(sameTitle("Salt, Fat, Acid & Heat", "salt fat acid and heat")).toBe(true));

  it("keeps a picked book's author only while the text still names it", () => {
    const c = savedBook({ source_title: "Invented Pantry Book", source_author: "Ada Example", source_isbn: null });
    expect(c.picked).toBe(true);
    expect(typedBook(c, "invented pantry book").author).toBe("Ada Example");
    expect(typedBook(c, "Invented Pan")).toEqual({ title: "Invented Pan", author: null, isbn: null, picked: false });
  });

  it("suggests the household's books, then the search's", () => {
    const found = [{ title: "Invented Pantry Book", author: "Ada Example", isbn: null }, { title: "Pantry Suppers", author: "B. Sample", isbn: "9780000000002" }];
    const s = suggestBooks(yours, found, { title: "pantry", author: null, isbn: null, picked: false });
    expect(s.yours.map((b) => b.title)).toEqual(["Invented Pantry Book"]);
    expect(s.found.map((b) => b.title)).toEqual(["Pantry Suppers"]);
    expect(suggestBooks(yours, found, { title: "p", author: null, isbn: null, picked: false }).found).toEqual([]);
  });

  it("a typed household book takes its spelling and author", () => {
    expect(resolveBook({ title: " invented  pantry book", author: null, isbn: null, picked: false }, yours))
      .toEqual({ title: "Invented Pantry Book", author: "Ada Example", isbn: null });
    expect(resolveBook({ title: "  ", author: null, isbn: null, picked: false }, yours)).toEqual({ title: null, author: null, isbn: null });
  });

  it("other names", () => {
    const names = otherNames(sources);
    expect(names).toEqual(["Grandma's cards"]);
    expect(suggestNames(names, "")).toEqual(["Grandma's cards"]);
    expect(suggestNames(names, "grandma's cards")).toEqual([]);
    expect(resolveName("GRANDMA'S CARDS", names)).toBe("Grandma's cards");
    expect(resolveName(" ", names)).toBeNull();
  });

  it("labels", () => {
    expect(page("88")).toBe("p. 88");
    expect(page("12 - 13")).toBe("pp. 12–13");
    expect(sourceLabel({ source_kind: "book", source_title: "Invented Pantry Book", source_author: "Ada Example", source_ref: "88" }))
      .toBe("Invented Pantry Book · Ada Example, p. 88");
    expect(sourceLabel({ source_kind: "book", source_title: null, source_ref: null })).toBe("Unknown book");
    expect(sourceLabel({ source_kind: "other", source_title: null, source_ref: "blue binder" })).toBe("Other, blue binder");
  });

  it("labels a web recipe with its site and author", () => {
    expect(sourceLabel({ source_kind: "web", source_title: "Example Kitchen", source_author: "Pat Example", source_ref: null }))
      .toBe("Example Kitchen · by Pat Example");
    expect(sourceLabel({ source_kind: "web", source_title: "Example Kitchen", source_ref: null })).toBe("Example Kitchen");
    // no saved site name: the link's domain
    expect(webSource({ source_title: null, source_author: null, source_url: "https://www.pots.example.org/stew" }))
      .toEqual({ site: "pots.example.org", author: null });
    expect(webSource({ source_title: " ", source_author: "", source_url: "not a link" })).toEqual({ site: "A website", author: null });
  });
});
