import { describe, expect, it } from "vitest";
import { mergeLine } from "./queries";
import type { Draft, DraftLine } from "./types";

const line = (id: number, quantity: number) => ({ id, quantity, name: `item ${id}` }) as unknown as DraftLine;
const draft = (lines: DraftLine[]) => ({ id: 7, status: "ready", lines }) as unknown as Draft;

describe("mergeLine: the server answers a line edit with the line, not the draft", () => {
  it("replaces the changed line and keeps the rest of the draft", () => {
    const d = draft([line(1, 1), line(2, 1)]);
    const out = mergeLine(d, line(2, 3))!;
    expect(out.id).toBe(7);
    expect(out.lines.map((l) => [l.id, l.quantity])).toEqual([[1, 1], [2, 3]]);
  });

  it("appends an added line", () => {
    const out = mergeLine(draft([line(1, 1)]), line(9, 2))!;
    expect(out.lines.map((l) => l.id)).toEqual([1, 9]);
  });

  it("takes a whole draft as it is", () => {
    const whole = draft([line(5, 1)]);
    expect(mergeLine(draft([line(1, 1)]), whole)).toBe(whole);
  });

  it("leaves no cached draft alone", () => {
    expect(mergeLine(undefined, line(1, 1))).toBeUndefined();
  });
});
