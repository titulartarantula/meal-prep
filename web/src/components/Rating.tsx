// Rating a dinner, as on the Android rating screen: family 1–5, "Would you make it for company?" (optional, choosing
// the same answer again clears it), a note, the recipe's ratings "So far" and its earlier notes. And the "How was …?"
// banner for the newest dinner not rated yet, with Later (hidden on this browser until tomorrow).
import { useEffect, useState } from "react";
import { usePendingRatings, useRate, useRecipe } from "../api/queries";
import type { PendingRating, PlanEntry, RatingSummary } from "../api/types";
import { formatAvg } from "../lib/format";
import { DAYS_LONG, parseISO, shortDate, toISO } from "../lib/weeks";
import { Button, ErrorNote, Modal, StarsInput } from "./ui";

export const COMPANY = [["yes", "Yes"], ["maybe", "Maybe"], ["no", "No"]] as const;
type Company = (typeof COMPANY)[number][0];

/** "How was Tuesday's Chili?" (or without the night when it has none). */
export function ratingQuestion(title: string, day: number | null | undefined): string {
  return day != null ? `How was ${DAYS_LONG[day]}'s ${title}?` : `How was ${title}?`;
}

/** "Family 4.5/5 · Company: yes · “less salt”", or null before the first rating. */
export function summaryText(r: RatingSummary | undefined): string | null {
  if (!r || r.times_rated === 0) return null;
  return [r.avg_family != null ? `Family ${formatAvg(r.avg_family)}/5` : null, r.company ? `Company: ${r.company}` : null,
    r.notes[0]?.note ? `“${r.notes[0].note}”` : null].filter(Boolean).join(" · ");
}

export function RateDialog({ entry, onClose }: { entry: PlanEntry | null; onClose: () => void }) {
  const rate = useRate(entry?.week ?? "");
  const recipe = useRecipe(entry?.recipe_id ?? null).data;
  const [stars, setStars] = useState(0);
  const [company, setCompany] = useState<Company | null>(null);
  const [note, setNote] = useState("");
  const [shown, setShown] = useState<number | null>(null);
  if (entry && shown !== entry.id) {
    setShown(entry.id);
    setStars(entry.rating?.family ?? 0);
    setCompany((entry.rating?.company as Company | null | undefined) ?? null);
    setNote(entry.rating?.note ?? "");
  }
  useEffect(() => { if (entry) rate.reset(); }, [entry?.id]);   // eslint-disable-line react-hooks/exhaustive-deps
  const close = () => { setShown(null); onClose(); };
  const save = () => entry && rate.mutate({ id: entry.id, rating: { family: stars, company, note: note.trim() || null } }, { onSuccess: close });
  const soFar = summaryText(recipe?.ratings);
  const notes = recipe?.ratings.notes ?? [];
  const title = entry?.title ?? recipe?.title ?? "this recipe";
  return (
    <Modal open={!!entry} onClose={close} title={entry ? ratingQuestion(title, entry.day) : ""}
      footer={<>
        {entry?.rating && <Button kind="ghost" onClick={() => rate.mutate({ id: entry.id, rating: null }, { onSuccess: close })}>Remove rating</Button>}
        <span className="spacer" />
        <Button kind="outline" onClick={close}>Cancel</Button>
        <Button kind="primary" onClick={save} disabled={!stars || rate.isPending}>{entry?.rating ? "Update rating" : "Save rating"}</Button>
      </>}>
      {soFar && <p className="muted small">So far: {soFar}</p>}
      <fieldset className="field-set">
        <legend>Family: was it a hit? <small>1 = not again, 5 = a big hit</small></legend>
        <StarsInput value={stars} onChange={setStars} label="Rating" />
      </fieldset>
      <fieldset className="field-set">
        <legend>Would you make it for company? <small>Optional. Choose your answer again to clear it.</small></legend>
        <div className="seg company" role="radiogroup" aria-label="Would you make it for company?">
          {COMPANY.map(([v, label]) => (
            <button key={v} type="button" role="radio" aria-checked={company === v} className={company === v ? "on" : ""}
              onClick={() => setCompany(company === v ? null : v)}>{label}</button>
          ))}
        </div>
      </fieldset>
      <label className="field">
        <span>Note <small>(optional)</small></span>
        <textarea rows={3} value={note} maxLength={500} onChange={(e) => setNote(e.target.value)}
          placeholder="Too salty, kids loved it, double the sauce next time…" />
        <small>Notes show on the recipe and here the next time it's rated.</small>
      </label>
      {notes.length > 0 && (
        <section aria-labelledby="earlier-notes">
          <h3 className="section-h" id="earlier-notes">Earlier notes</h3>
          <ul className="earlier-notes">
            {notes.map((n, i) => (
              <li key={i}>“{n.note}”{n.date && <span className="muted"> — {shortDate(parseISO(n.date))}</span>}</li>
            ))}
          </ul>
        </section>
      )}
      <ErrorNote error={rate.error} />
    </Modal>
  );
}

const LATER = "mealprep.rateLater";

function laterKeys(): string[] {
  try {
    return JSON.parse(localStorage.getItem(LATER) ?? "[]");
  } catch {
    return [];
  }
}

/** The newest dinner not rated yet unless Later was chosen for it today, and the Later action. */
export function usePendingBanner(enabled: boolean): [PendingRating | null, () => void] {
  const pending = usePendingRatings(enabled).data ?? [];
  const today = toISO(new Date());
  const [later, setLater] = useState(laterKeys);
  const first = pending.find((p) => !later.includes(`${p.entry_id}:${today}`)) ?? null;
  const dismiss = () => {
    if (!first) return;
    // only today's keys are kept: tomorrow the dinner asks again
    const keys = [...later.filter((k) => k.endsWith(`:${today}`)), `${first.entry_id}:${today}`];
    localStorage.setItem(LATER, JSON.stringify(keys));
    setLater(keys);
  };
  return [first, dismiss];
}

/** "How was Tuesday's Chili?" with Rate it and Later. */
export function RatingBanner({ pending, onRate, onLater }: { pending: PendingRating; onRate: () => void; onLater: () => void }) {
  return (
    <section className="rate-banner" aria-label="Rate a dinner">
      <div className="rate-banner-text">
        <b>{ratingQuestion(pending.title, pending.day)}</b>
        <small>{shortDate(parseISO(pending.date))} · a quick rating helps pick family favourites.</small>
      </div>
      <div className="rate-banner-actions">
        <Button kind="primary" size="sm" icon="star" onClick={onRate}>Rate it</Button>
        <Button kind="ghost" size="sm" onClick={onLater}>Later</Button>
      </div>
    </section>
  );
}

/** The plan entry a pending rating stands for (the dialog takes plan entries). */
export function pendingEntry(p: PendingRating): PlanEntry {
  return { id: p.entry_id, week: p.week, recipe_id: p.recipe_id, day: p.day, multiplier: p.multiplier, rating: null, title: p.title };
}
