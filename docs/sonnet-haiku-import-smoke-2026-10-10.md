# Meal Prep: Sonnet 5.5 vs Haiku 5.5 recipe-import smoke test (2026-10-10)

**Scope:** exploratory, matched-input comparison of the local 0.10.0 import extraction code. No live service settings, database records, deployment, or app release changed. Each call used the same production prompt and local Claude CLI provider behavior (no tools for text, Read for images); modelUsage confirmed the requested model ID in every successful call. Data was invented, not the household's recipes.

| Case | Sonnet 5.5 | Haiku 5.5 |
| --- | --- | --- |
| Text collection (3 recipes; index decoy) | 3/3 recipes, all ingredients/methods/yields and source correct | Identical normalized outputs, 3/3 |
| Cookbook photos (2 pages deliberately supplied in reverse order; neighbouring recipe fragments) | One complete Lemon Lentil Soup: 6 ingredients, 4 steps, correct yield. Named an unspecified onion **yellow onion** in grocery structuring. | Same recipe, ingredients, method and yield. Preserved the unspecified **onion** instead of adding a colour. |
| Scanned-document page images (2 complete recipes; index decoy) | 2/2 recipes; correct ingredients, methods, yields, tip and “From Nana Ruth” provenance. | 2/2 recipes, same ingredients, methods, yields. **Incorrect provenance on both:** treated the document heading “Family Recipe Book” as a cookbook source (and page 7/8); misclassified “From Nana Ruth” as the cookbook's author rather than the recipe's “from” source. Tip wording dropped “Tip:” but kept its content. |

**Successful-call wall time:** Sonnet 135.70 s across four calls (text 9.27, cookbook photo 60.12, photo ingredient structuring 8.03, scanned collection 58.28); Haiku 53.50 s (9.46, 17.68, 7.38, 18.98). This excludes a failed first Haiku scan attempt; the importer's built-in retry succeeded. The CLI failure text began with an `unrecognized_model` warning, but the actual cause was not captured; subsequent successful `modelUsage` was exactly `claude-haiku-5-5`. Do not interpret these four samples as a reliable latency distribution or API billing estimate; calls used the subscription CLI, not the pay-per-use SDK.

**Verdict:** Haiku is promising for speed and basic recipe extraction, but **do not replace Sonnet globally yet**. The scanned-document provenance mistakes directly violate the source rule in the production prompt and would persist in imported recipes. The sample is small and unusually clean; there is no test yet on real cookbook photos, messy PDFs, long chunk-boundary documents, or the app's prep-plan/cart-matching prompts. Keep the live model unchanged. If trying Haiku later, evaluate it per task and require review/quality gates for document provenance.

Raw synthetic inputs, page images and model outputs: `/tmp/mealprep-sonnet-haiku-20261010/` on the OpenClaw host (temporary; not committed). The scanned case exercised the same `Document(kind='pdf', pages=[PNG bytes])` path as a rendered scanned PDF, but did not exercise PDF file parsing or phone UI.
