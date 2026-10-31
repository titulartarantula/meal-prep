import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useSearchParams } from "react-router-dom";
import { makeList, useCan, useCreateDraft, useDefaultCartWeek, useStaples, useWeekDraft, useWeeks } from "../api/queries";
import type { ListItem } from "../api/types";
import { Icon } from "../components/Icon";
import { Button, ErrorNote, Loading } from "../components/ui";
import { amount, agoInline } from "../lib/format";
import { useCompact } from "../lib/media";
import { weekName, weekNameInline, weekOf, weekRange } from "../lib/weeks";

export function Shopping() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const can = useCan();
  const thisWeek = weekOf(new Date());
  const weeks = useWeeks(thisWeek, 5).data ?? [];
  const def = useDefaultCartWeek().data;
  const staples = useStaples().data ?? [];
  const [chosen, setChosen] = useState<string[] | null>(params.get("week") ? [params.get("week")!] : null);
  const [ticked, setTicked] = useState<number[] | null>(null);
  const [needed, setNeeded] = useState<Record<string, boolean>>({});
  const create = useCreateDraft();
  const compact = useCompact();

  const sel = chosen ?? (def?.week ? [def.week] : []);
  const staplesOn = ticked ?? staples.filter((s) => s.weekly).map((s) => s.id);
  useEffect(() => setNeeded({}), [sel.join(), staplesOn.join()]);  // eslint-disable-line react-hooks/exhaustive-deps

  const list = useQuery({
    queryKey: ["list", sel, staplesOn],
    queryFn: () => makeList(sel, staplesOn),
    enabled: sel.length > 0 && (ticked !== null || staples.length >= 0),
  });
  const existing = useWeekDraft(sel.length === 1 ? sel[0] : null).data;

  const items = useMemo(() => (list.data ?? []).map((i) => ({ ...i, needed: needed[i.key] ?? i.needed })), [list.data, needed]);
  const toBuy = items.filter((i) => i.needed && !(i.staple && i.recipes.every((r) => r === "Staples")));
  const stapleLines = items.filter((i) => i.staple && i.recipes.every((r) => r === "Staples"));
  const have = items.filter((i) => !i.needed);
  const count = items.filter((i) => i.needed).length;

  const toggleWeek = (w: string) => setChosen(sel.includes(w) ? sel.filter((x) => x !== w) : [...sel, w].sort());
  const build = () => create.mutate({ items: items as ListItem[], weeks: sel }, { onSuccess: (d) => navigate(`/cart/${d.id}`) });

  const weekChoice = (
    <>
      <h2>Shop for</h2>
      <div className="week-chips">
        {/* phones list only the weeks that have something to shop for */}
        {weeks.filter((w) => !compact || w.entries || sel.includes(w.week)).map((w) => (
          <button key={w.week} type="button" className={`chip-btn${sel.includes(w.week) ? " on" : ""}`} aria-pressed={sel.includes(w.week)}
            onClick={() => toggleWeek(w.week)} disabled={!w.entries && !sel.includes(w.week)}>
            {sel.includes(w.week) && <Icon name="check" size={14} />}
            <span><b>{weekName(w.week)}</b><small>{weekRange(w.week)} · {w.entries ? `${w.entries} recipe${w.entries > 1 ? "s" : ""}` : "nothing planned"}{w.carted ? " · cart sent" : ""}</small></span>
          </button>
        ))}
      </div>
    </>
  );
  const staplesChoice = staples.length > 0 && (
    <>
      <h3 className="section-h">Staples</h3>
      <ul className="staples">
        {staples.map((s) => (
          <li key={s.id}>
            <label>
              <input type="checkbox" checked={staplesOn.includes(s.id)}
                onChange={(e) => setTicked(e.target.checked ? [...staplesOn, s.id] : staplesOn.filter((x) => x !== s.id))} />
              <span>{s.name}<small>{s.last_bought ? `Last bought ${agoInline(s.last_bought)}` : "Not bought yet"}</small></span>
            </label>
          </li>
        ))}
      </ul>
    </>
  );
  const buildButton = can("cart.build")
    ? <Button kind="primary" size="lg" iconAfter="arrow" disabled={!count || create.isPending} onClick={build}>Build cart</Button>
    : <p className="fine">Only the household's purchaser can build the cart.</p>;
  const listBody = sel.length === 0 ? (
    <div className="empty-state"><Icon name="bag" size={26} /><b>Pick a week to shop for</b>
      <small>{compact ? "Weeks with recipes on the plan are listed above." : "Weeks with recipes on the plan are on the right."}</small></div>
  ) : list.isPending ? <Loading label="Making the list…" /> : list.error ? <ErrorNote error={list.error} onRetry={() => list.refetch()} /> : (
    <>
      {existing && existing.status !== "failed" && !existing.stale && (
        <div className="note">
          <Icon name="bag" size={17} />
          <span>{existing.status === "sent" ? `A cart for ${weekNameInline(sel[0])} was sent ${agoInline(existing.sent_at)}.` : `A cart for ${weekNameInline(sel[0])} is ${existing.status === "building" ? "being built" : "waiting for review"}.`}</span>
          <Button size="sm" kind="outline" onClick={() => navigate(`/cart/${existing.id}`)}>Open it</Button>
        </div>
      )}
      <ListSection title="To buy" items={toBuy} onToggle={(k, v) => setNeeded((n) => ({ ...n, [k]: v }))} />
      {stapleLines.length > 0 && <ListSection title="Staples" items={stapleLines} onToggle={(k, v) => setNeeded((n) => ({ ...n, [k]: v }))} />}
      <ListSection title="Probably have" hint="Pantry items and anything you unticked. Tick one to buy it." items={have}
        onToggle={(k, v) => setNeeded((n) => ({ ...n, [k]: v }))} />
    </>
  );

  // Phones: weeks and staples first, then the list, with Build cart docked above the tab bar.
  if (compact) {
    return (
      <main className="main" id="main">
        <div className="top">
          <div>
            <h1>Shopping list</h1>
            <div className="sub">Tick off what you already have; the rest goes into the cart.</div>
          </div>
        </div>
        <section className="summary" aria-label="Cart options">
          {weekChoice}
          {staplesChoice && (
            <details className="staples-fold">
              <summary>Staples <span className="muted">· {staplesOn.length} of {staples.length} on this list</span></summary>
              {staplesChoice}
            </details>
          )}
        </section>
        <div className="list-body">{listBody}</div>
        <p className="fine">Meal Prep finds each item at Loblaws (your usual picks first). You review everything before it's sent.</p>
        <ErrorNote error={create.error} />
        <div className="dock">
          <span className="dock-text"><b>{list.data ? count : "—"}</b> items to buy</span>
          {buildButton}
        </div>
      </main>
    );
  }

  return (
    <main className="main" id="main">
      <div className="top">
        <div>
          <div className="eyebrow">Shopping</div>
          <h1>Shopping list</h1>
          <div className="sub">Tick off what you already have; the rest goes into the cart.</div>
        </div>
      </div>
      <div className="two-col">
        <div className="col-scroll">
          {listBody}
        </div>

        <aside className="summary" aria-label="Cart options">
          {weekChoice}
          {staplesChoice}
          <div className="total"><span>Items to buy</span><b>{list.data ? count : "—"}</b></div>
          {buildButton}
          <p className="fine">Meal Prep finds each item at Loblaws (your usual picks first). You review everything before it's sent.</p>
          <ErrorNote error={create.error} />
        </aside>
      </div>
    </main>
  );
}

function ListSection({ title, hint, items, onToggle }: { title: string; hint?: string; items: ListItem[]; onToggle: (key: string, needed: boolean) => void }) {
  if (!items.length) return null;
  return (
    <section className="list-sec" aria-label={title}>
      <h2 className="list-h">{title} <span className="count">{items.length}</span></h2>
      {hint && <p className="muted small">{hint}</p>}
      <ul className="list">
        {items.map((i) => (
          <li key={i.key}>
            <label>
              <input type="checkbox" checked={i.needed} onChange={(e) => onToggle(i.key, e.target.checked)} />
              <span className="li-name">{i.name}{i.prep && <span className="muted">, {i.prep}</span>}</span>
              <span className="li-amt">{amount(i.qty, i.unit) || (i.staple_packs ? `${i.staple_packs} pack` : "")}</span>
              <span className="li-recipes">{i.recipes.join(", ")}</span>
            </label>
          </li>
        ))}
      </ul>
    </section>
  );
}
