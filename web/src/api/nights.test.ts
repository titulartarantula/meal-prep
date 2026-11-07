import { describe, expect, it } from "vitest";
import { dropItem, putItem, setTitle } from "./queries";
import type { Extra, Night } from "./types";
import { itemName, itemQty, storeSummary } from "../components/NightExtras";
import { weekWhat } from "../lib/format";

const item = (id: number, day: number | null, quantity = 1, name = `Thing ${id}`): Extra => ({
  id, week: "2026-11-15", day, term: name.toLowerCase(), key: `${name.toLowerCase()}|each`, quantity, created_at: "2026-11-06T20:00:00Z",
  product: { code: `P${id}`, name, brand: null },
});

describe("night names in the cached nights", () => {
  it("adds, renames and clears a name; a night with nothing left drops out", () => {
    let ns: Night[] | undefined = [];
    ns = setTitle(ns, 4, "Pizza night");
    expect(ns).toEqual([{ day: 4, title: "Pizza night", items: [] }]);
    ns = setTitle(ns, 1, "Leftovers");
    expect(ns!.map((n) => n.day)).toEqual([1, 4]);
    ns = setTitle(ns, 4, "Movie night");
    expect(ns!.find((n) => n.day === 4)!.title).toBe("Movie night");
    ns = setTitle(ns, 4, null);
    expect(ns!.map((n) => n.day)).toEqual([1]);
  });

  it("keeps a night whose name is cleared while it still has store items", () => {
    const ns = setTitle([{ day: 2, title: "Tacos", items: [item(1, 2)] }], 2, null)!;
    expect(ns).toEqual([{ day: 2, title: null, items: [item(1, 2)] }]);
  });

  it("leaves an unloaded cache alone", () => {
    expect(setTitle(undefined, 1, "x")).toBeUndefined();
    expect(putItem(undefined, item(1, 1))).toBeUndefined();
  });
});

describe("store items in the cached nights (writes answer with the one item)", () => {
  it("puts an added item on its night, creating the night", () => {
    const ns = putItem([{ day: 1, title: "Leftovers", items: [] }], item(5, 4, 2))!;
    expect(ns.map((n) => [n.day, n.items.map((i) => i.id)])).toEqual([[1, []], [4, [5]]]);
  });

  it("moves an item to another night, or off the nights for the whole week", () => {
    let ns = putItem([], item(5, 4))!;
    ns = putItem(ns, item(6, 4))!;
    ns = putItem(ns, { ...item(5, 4), day: 6 })!;
    expect(ns.map((n) => [n.day, n.items.map((i) => i.id)])).toEqual([[4, [6]], [6, [5]]]);
    ns = putItem(ns, { ...item(6, 4), day: null })!;
    expect(ns.map((n) => n.day)).toEqual([6]);
  });

  it("changes a quantity in place", () => {
    const ns = putItem(putItem([], item(5, 4))!, item(5, 4, 3))!;
    expect(ns[0].items.map((i) => i.quantity)).toEqual([3]);
  });

  it("removes an item; a named night stays", () => {
    const ns = dropItem([{ day: 4, title: "Pizza night", items: [item(5, 4)] }, { day: 5, title: null, items: [item(6, 5)] }], 6)!;
    expect(ns).toEqual([{ day: 4, title: "Pizza night", items: [item(5, 4)] }]);
    expect(dropItem(ns, 5)).toEqual([{ day: 4, title: "Pizza night", items: [] }]);
  });
});

describe("labels", () => {
  it("names an item by brand + product, else its search", () => {
    expect(itemName({ term: "frozen pizza", product: { code: "A", name: "Pepperoni Pizza", brand: "Hearthstone" } })).toBe("Hearthstone Pepperoni Pizza");
    expect(itemName({ term: "frozen pizza", product: null })).toBe("frozen pizza");
  });

  it("shows ×n, or grams for a weighed product", () => {
    expect(itemQty({ quantity: 2, product: { code: "A", name: "x" } })).toBe("×2");
    expect(itemQty({ quantity: 300, product: { code: "B", name: "Ham", weighted: true, interval: 100 } })).toMatch(/300\s?g/);
  });

  it("sums up a night's store items", () => {
    expect(storeSummary([item(1, 4, 2, "Pizza"), item(2, 4, 1, "Caesar Salad Kit")])).toBe("Pizza ×2 · Caesar Salad Kit");
  });

  it("says what a week has to shop for", () => {
    expect(weekWhat(2, 3)).toBe("2 recipes · 3 added items");
    expect(weekWhat(0, 1)).toBe("1 added item");
    expect(weekWhat(1, 0)).toBe("1 recipe");
    expect(weekWhat(0, 0)).toBe("nothing planned");
  });
});
