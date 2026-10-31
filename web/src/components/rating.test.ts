import { describe, expect, it } from "vitest";
import type { PersonRating, RecipeDetail } from "../api/types";
import { companyText, cookedOn, initials, lastRatingText, personName, ratingOf, summaryText } from "./Rating";

const pr = (member_id: number | null, family: number, note: string | null = null): PersonRating => ({
  member_id, name: member_id == null ? "Family" : `M${member_id}`, kind: member_id == null ? "family" : "account", family,
  note, rated_at: "2026-10-20T23:00:00Z", entered_by: null, entered_by_name: null });
const night = (entry_id: number, day: number | null, date: string | null, ratings: PersonRating[]) =>
  ({ entry_id, week: "2026-10-18", day, date, multiplier: 1, ratings, rating: null, my_rating: null, rating_avg: null, rating_count: ratings.length });

const ME = 7;
const detail = {
  history: [
    night(1, 3, "2026-10-21", [pr(2, 3)]),
    night(2, 3, "2026-10-21", [pr(ME, 4), pr(null, 5)]),
    night(3, 5, "2026-10-23", []),
    night(4, null, null, []),
  ],
  rated_history: [{ date: "2026-10-12", ratings: [pr(ME, 2, "dry"), pr(2, 4)], rating: pr(ME, 2, "dry") }],
} as unknown as RecipeDetail;

describe("cookedOn: what a day stands for when rating from the recipe, for one person", () => {
  it("a planned night that day, the one this person rated first", () => {
    expect(cookedOn(detail, "2026-10-21", null, ME)).toMatchObject({ night: 3, rating: pr(ME, 4) });
    expect(cookedOn(detail, "2026-10-21", null, ME).ratings).toHaveLength(2);
    expect(cookedOn(detail, "2026-10-21", 2, ME)).toMatchObject({ night: 3, rating: pr(2, 3) });
    expect(cookedOn(detail, "2026-10-23", null, ME)).toEqual({ night: 5, rating: null, ratings: [] });
  });
  it("else the recipe's own ratings that day, else nothing", () => {
    expect(cookedOn(detail, "2026-10-12", null, ME)).toMatchObject({ night: null, rating: pr(ME, 2, "dry") });
    expect(cookedOn(detail, "2026-10-12", 9, ME)).toMatchObject({ night: null, rating: null });
    expect(cookedOn(detail, "2026-10-13", null, ME)).toEqual({ night: null, rating: null, ratings: [] });
    expect(cookedOn(undefined, "2026-10-13")).toEqual({ night: null, rating: null, ratings: [] });
  });
  it("without a member (the shared token) it's the Family's rating", () => {
    expect(ratingOf(detail.history[1].ratings, null, null)).toEqual(pr(null, 5));
  });
});

describe("lastRatingText", () => {
  const people = [{ member_id: ME, name: "Sam", kind: "account", family: 4, rated_at: "2026-10-20T16:00:00Z", avg: 4, count: 1 },
    { member_id: 4, name: "Pip", kind: "child", family: 5, rated_at: "2026-10-12T16:00:00Z", avg: 5, count: 1 }];
  it("is null before the person's first rating", () => {
    expect(lastRatingText(undefined)).toBeNull();
    expect(lastRatingText({ people: [] } as never, null, ME)).toBeNull();
  });
  it("gives your score and the day, or a child's", () => {
    expect(lastRatingText({ people } as never, null, ME)).toBe("Your last rating: 4/5 on Oct 20");
    expect(lastRatingText({ people } as never, 4, ME)).toBe("Pip's last rating: 5/5 on Oct 12");
  });
});

describe("names, chips and the summary line", () => {
  it("marks your own row, Family's chip says Fam", () => {
    expect(personName({ member_id: ME, name: "Sam" }, ME)).toBe("Sam (you)");
    expect(personName({ member_id: null, name: "Family" }, null)).toBe("Family");
    expect(initials({ name: "robin", kind: "account" })).toBe("R");
    expect(initials({ name: "Family", kind: "family" })).toBe("Fam");
  });
  it("says the average and how many ratings", () => {
    expect(summaryText({ times_rated: 2, rating_count: 5, avg_family: 3.8, company: "yes", notes: [{ note: "spicy" }] } as never))
      .toBe("Average 3.8/5 from 5 ratings · Company: yes · “spicy”");
    expect(summaryText({ times_rated: 1, rating_count: 1, avg_family: 4, company: null, notes: [] } as never)).toBe("Average 4.0/5 from 1 rating");
    expect(summaryText({ times_rated: 0 } as never)).toBeNull();
  });
  it("says the household's Good for company verdict and who set it", () => {
    expect(companyText({ company: "yes", company_set_by_name: "Sam" } as never)).toBe("Good for company: Yes · set by Sam");
    expect(companyText({ company: "maybe", company_set_by_name: null } as never)).toBe("Good for company: Maybe");
    expect(companyText({ company: null } as never)).toBe("Good for company: not decided yet");
    expect(companyText(undefined)).toBe("Good for company: not decided yet");
  });
});
