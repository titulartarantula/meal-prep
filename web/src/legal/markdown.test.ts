import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { inline, legalDoc } from "./markdown";

describe("legal pages' markdown", () => {
  it("escapes HTML and keeps only the supported inline marks", () => {
    expect(inline("a <b> & **bold** and _it_ `code_x`")).toBe(
      "a &lt;b&gt; &amp; <strong>bold</strong> and <em>it</em> <code>code_x</code>");
  });

  it("links https URLs, markdown links and emails; own pages stay relative", () => {
    expect(inline("see [the policy](https://mealprep.drwhom.ca/privacy).")).toBe('see <a href="/privacy">the policy</a>.');
    expect(inline("at https://example.com/a_b, ok")).toBe('at <a href="https://example.com/a_b">https://example.com/a_b</a>, ok');
    expect(inline("Questions: someone@example.com.")).toBe(
      'Questions: <!--email_off--><a href="mailto:someone@example.com">someone@example.com</a><!--/email_off-->.');
    expect(inline("[x](javascript:alert(1))")).toBe("[x](javascript:alert(1))");
  });

  it("reads title, date, headings, paragraphs and lists", () => {
    const d = legalDoc("# T\n\n_Published at https://x.test/p_\n\n_Last updated: 1 May 2026_\n\nOne\ntwo.\n\n## H\n\n- a\n  more\n- b\n");
    expect(d.title).toBe("T");
    expect(d.updated).toBe("1 May 2026");
    expect(d.html).toBe("<p>One two.</p>\n<h2>H</h2>\n<ul>\n<li>a more</li>\n<li>b</li>\n</ul>");
  });

  it.each(["privacy-policy.md", "terms.md"])("docs/web/%s has a title, a date and no stray markdown", (f) => {
    const d = legalDoc(readFileSync(new URL(`../../../docs/web/${f}`, import.meta.url), "utf8"));
    expect(d.title).toMatch(/^Meal Prep on the web: /);
    expect(d.updated).toBe("31 October 2026");
    expect(d.html).not.toMatch(/\*\*|`|\]\(|(^|\s)_\w/);
  });
});
