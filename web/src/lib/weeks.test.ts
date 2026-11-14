import { describe, expect, it } from "vitest";
import { DAYS, DAYS_LONG, nightDate, shiftWeek, toISO, weekName, weekNameInline, weekOf, weekRange } from "./weeks";
import { agoInline, amount, formatMinutes, formatQty } from "./format";
import { lineCost } from "../api/types";

describe("weeks", () => {
  it("names a week by its Monday (weeks run Monday–Sunday)", () => {
    expect(weekOf(new Date(2026, 9, 30))).toBe("2026-10-26");   // Friday
    expect(weekOf(new Date(2026, 9, 26))).toBe("2026-10-26");   // Monday
    expect(weekOf(new Date(2026, 10, 1))).toBe("2026-10-26");   // Sunday: the end of the week, not the start
    expect(weekOf(new Date(2026, 10, 2))).toBe("2026-11-02");   // Monday
    expect(weekOf(new Date(2026, 10, 7))).toBe("2026-11-02");   // Saturday
  });
  it("shifts across months and DST", () => {
    expect(shiftWeek("2026-10-26", 1)).toBe("2026-11-02");      // DST ends Nov 1 in Toronto
    expect(shiftWeek("2026-11-02", -1)).toBe("2026-10-26");
    expect(toISO(nightDate("2026-10-26", 0))).toBe("2026-10-26"); // night 0 = Monday
    expect(toISO(nightDate("2026-10-26", 6))).toBe("2026-11-01"); // night 6 = Sunday, across DST
  });
  it("day names start on Monday", () => {
    expect(DAYS[0]).toBe("Mon");
    expect(DAYS[6]).toBe("Sun");
    expect(DAYS_LONG[3]).toBe("Thursday");
    expect(nightDate("2026-11-16", 3).getDay()).toBe(4); // night 3 of a Monday week is a Thursday
  });
  it("labels", () => {
    const today = new Date(2026, 9, 30);
    expect(weekName("2026-10-26", today)).toBe("This week");
    expect(weekName("2026-11-02", today)).toBe("Next week");
    expect(weekName("2026-10-19", today)).toBe("Last week");
    expect(weekName("2026-11-09", today)).toBe("Week of Nov 9");
    expect(weekName("2026-10-26", new Date(2026, 10, 1))).toBe("This week");   // on Sunday it's still this week
    expect(weekNameInline("2026-11-09", today)).toBe("the week of Nov 9");
    expect(weekNameInline("2026-11-02", today)).toBe("next week");
    expect(weekRange("2026-10-26")).toBe("Oct 26 – Nov 1");
    expect(weekRange("2026-11-16")).toBe("Nov 16 – 22");
  });
});

describe("format", () => {
  it("quantities", () => {
    expect(formatQty(1.5)).toBe("1½");
    expect(formatQty(0.333)).toBe("⅓");
    expect(formatQty(2)).toBe("2");
    expect(amount(250, "g")).toBe("250 g");
    expect(amount(null, null)).toBe("");
  });
  it("minutes", () => {
    expect(formatMinutes(35)).toBe("35 min");
    expect(formatMinutes(75)).toBe("1 h 15");
    expect(formatMinutes(120)).toBe("2 h");
    expect(formatMinutes(null)).toBeNull();
  });
  it("relative times in a sentence", () => {
    const now = new Date(2026, 9, 30, 12);
    expect(agoInline(new Date(2026, 9, 29, 9).toISOString(), now)).toBe("yesterday");
    expect(agoInline(new Date(2026, 9, 30, 10).toISOString(), now)).toBe("2 h ago");
  });
  it("line cost counts grams for products sold by weight", () => {
    expect(lineCost({ product: { code: "a", name: "x", price: 2.99 }, quantity: 2, removed: false })).toBeCloseTo(5.98);
    expect(lineCost({ product: { code: "a", name: "x", price: 7.7, price_quantity: 1000, weighted: true }, quantity: 1800, removed: false })).toBeCloseTo(13.86);
    expect(lineCost({ product: { code: "a", name: "x", price: 2.99 }, quantity: 2, removed: true })).toBe(0);
  });
});
