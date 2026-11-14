import { describe, expect, it } from "vitest";
import { errorText } from "./client";

describe("errorText", () => {
  it("shows the server's sentence, also when the public site passes a 502 on as 424", () => {
    const detail = "example.org refused to show that page to the Meal Prep server (it blocks automated readers).";
    expect(errorText({ detail }, 502)).toBe(detail);
    expect(errorText({ detail }, 424)).toBe(detail);
  });

  it("never shows Cloudflare's own error text", () => {
    const cf = {
      title: "Error 502: Bad gateway",
      detail: "The origin web server returned an invalid or incomplete response to Cloudflare.",
      status: 502,
      ray_id: "0000000000000000",
    };
    expect(errorText(cf, 502)).toBe("The Meal Prep server couldn't be reached just now. Try again in a minute.");
    expect(errorText("<!DOCTYPE html><html>… cloudflare …</html>", 502)).toMatch(/couldn't be reached/);
    expect(errorText({ ...cf, status: 524 }, 524)).toMatch(/took too long/);
  });

  it("keeps the generic fallbacks", () => {
    expect(errorText(undefined, 424)).toMatch(/import source/);
    expect(errorText(undefined, 500)).toBe("Something went wrong on the server.");
  });
});
