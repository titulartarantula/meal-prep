import { describe, expect, it } from "vitest";
import type { RecipeDetail } from "../api/types";
import { cookedOn, lastRatingText } from "./Rating";

const r = (family: number, note: string | null = null) => ({ family, company: null, note, rated_at: "2026-10-20T23:00:00Z" });

const detail = {
  history: [
    { entry_id: 1, week: "2026-10-18", day: 3, date: "2026-10-21", multiplier: 1, rating: null },
    { entry_id: 2, week: "2026-10-18", day: 3, date: "2026-10-21", multiplier: 1, rating: r(4) },
    { entry_id: 3, week: "2026-10-18", day: 5, date: "2026-10-23", multiplier: 1, rating: null },
    { entry_id: 4, week: "2026-10-25", day: null, date: null, multiplier: 1, rating: null },
  ],
  rated_history: [{ date: "2026-10-12", rating: r(2, "dry") }],
} as unknown as RecipeDetail;

describe("cookedOn: what a day stands for when rating from the recipe", () => {
  it("a planned night that day, the rated one first", () => {
    expect(cookedOn(detail, "2026-10-21")).toEqual({ night: 3, rating: r(4) });
    expect(cookedOn(detail, "2026-10-23")).toEqual({ night: 5, rating: null });
  });
  it("else the recipe's own rating that day, else nothing", () => {
    expect(cookedOn(detail, "2026-10-12")).toEqual({ night: null, rating: r(2, "dry") });
    expect(cookedOn(detail, "2026-10-13")).toEqual({ night: null, rating: null });
    expect(cookedOn(undefined, "2026-10-13")).toEqual({ night: null, rating: null });
  });
});

describe("lastRatingText", () => {
  it("is null before the first rating", () => {
    expect(lastRatingText(undefined)).toBeNull();
    expect(lastRatingText({ times_cooked: 0, times_rated: 0, notes: [], imported_ratings: 0 } as never)).toBeNull();
  });
  it("gives the score and the day it was rated", () => {
    expect(lastRatingText({ last_family: 4, last_rated_at: "2026-10-20T16:00:00Z" } as never)).toBe("Your last rating: 4/5 on Oct 20");
  });
});
