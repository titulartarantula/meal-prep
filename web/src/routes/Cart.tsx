import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ApiError } from "../api/client";
import { useCan, useDraft, useDraftWrite } from "../api/queries";
import { lineCost, qtyRule, type Draft, type DraftLine } from "../api/types";
import { Icon } from "../components/Icon";
import { Handoff } from "../components/Handoff";
import { ProductChooser } from "../components/ProductChooser";
import { Button, ErrorNote, IconButton, Loading, Stepper, useToast } from "../components/ui";
import { amount, agoInline, formatMoney } from "../lib/format";
import { weekName } from "../lib/weeks";

type Filter = "all" | "new" | "added" | "removed" | "check";

export function Cart() {
  const id = Number(useParams().id);
  const draft = useDraft(id);
  const navigate = useNavigate();
  if (draft.isPending) return <main className="main" id="main"><Loading label="Loading the cart…" /></main>;
  if (draft.error) return <main className="main" id="main"><ErrorNote error={draft.error} onRetry={() => draft.refetch()} /></main>;
  const d = draft.data;
  const weeks = d.weeks.map((w) => weekName(w)).join(" + ");
  return (
    <main className="main" id="main">
      <div className="top">
        <div>
          <div className="eyebrow">Shopping · {weeks}</div>
          <h1>{d.status === "sent" ? "Cart sent to Loblaws" : d.status === "building" ? "Building your cart" : "Review your cart"}</h1>
          <div className="sub">{subline(d)}</div>
        </div>
        {d.status !== "building" && <Button kind="ghost" icon="left" onClick={() => navigate(`/shopping?week=${d.weeks[0]}`)}>Shopping list</Button>}
      </div>
      {d.status === "building" ? <Building d={d} /> : d.status === "failed" ? (
        <div className="empty-state">
          <Icon name="alert" size={26} />
          <b>The cart couldn't be built</b>
          <small>{d.error ?? "Loblaws didn't answer. Nothing was added anywhere."}</small>
          <Button kind="primary" onClick={() => navigate(`/shopping?week=${d.weeks[0]}`)}>Back to the list</Button>
        </div>
      ) : <Review d={d} />}
    </main>
  );
}

function subline(d: Draft) {
  const n = d.lines.filter((l) => !l.removed).length;
  if (d.status === "building") return "Finding each item at Loblaws, your usual picks first.";
  if (d.status === "sent") return `Sent ${agoInline(d.sent_at)} · ${n} lines`;
  const usual = d.lines.filter((l) => l.source === "memory").length;
  return `Built ${agoInline(d.created_at)} · ${n} lines · ${usual} are your usual picks`;
}

function Building({ d }: { d: Draft }) {
  const pct = d.progress.total ? Math.round((100 * d.progress.done) / d.progress.total) : 0;
  return (
    <div className="building" role="status">
      <div className="bar" aria-hidden="true"><span style={{ width: `${pct}%` }} /></div>
      <p><b>Finding products… {d.progress.done} of {d.progress.total}</b></p>
      <p className="muted">New items take about half a minute each; you can leave this page and come back.</p>
    </div>
  );
}

function Review({ d }: { d: Draft }) {
  const can = useCan();
  const { patchLine, addLine, send } = useDraftWrite(d.id);
  const toast = useToast();
  const [filter, setFilter] = useState<Filter>("all");
  const [chooser, setChooser] = useState<{ line: DraftLine | null; term?: string } | null>(null);
  const [adding, setAdding] = useState("");
  const editable = d.status === "ready" && !d.stale && can("cart.build");

  const counts = {
    all: d.lines.length,
    new: d.lines.filter((l) => l.source === "ai" && !l.removed).length,
    added: d.lines.filter((l) => l.extra_id != null).length,
    check: d.lines.filter((l) => !l.removed && (l.needs_check || !l.product)).length,
    removed: d.lines.filter((l) => l.removed).length,
  };
  const shown = d.lines.filter((l) => filter === "all" ? true : filter === "new" ? l.source === "ai" && !l.removed
    : filter === "added" ? l.extra_id != null : filter === "removed" ? l.removed : !l.removed && (l.needs_check || !l.product));
  const live = d.lines.filter((l) => !l.removed && l.product && l.quantity);
  const subtotal = live.reduce((s, l) => s + lineCost(l), 0);
  const items = live.reduce((s, l) => s + (l.product?.weighted ? 1 : l.quantity ?? 0), 0);   // a weighed item counts once

  const onErr = (e: Error) => toast({ text: e instanceof ApiError && e.status === 409 ? "This cart changed on the server; reloaded." : `Couldn't save: ${e.message}` });
  const doSend = () => send.mutate(undefined, { onError: (e) => toast({ text: e.message }) });

  return (
    <div className="cart-body">
      <div className="cart-main">
        {d.stale && d.status !== "sent" && (
          <div className="note warn"><Icon name="alert" size={17} /><span>The week's recipes changed since this cart was built, so it can't be sent. Build a new one from the shopping list.</span></div>
        )}
        <div className="toolbar">
          <div className="seg" role="tablist" aria-label="Show">
            {(["all", "new", "added", "check", "removed"] as const).filter((f) => f === "all" || counts[f]).map((f) => (
              <button key={f} type="button" role="tab" aria-selected={filter === f} className={filter === f ? "on" : ""} onClick={() => setFilter(f)}>
                {{ all: "All", new: "New picks", added: "Added", check: "To check", removed: "Removed" }[f]} <span>{counts[f]}</span>
              </button>
            ))}
          </div>
          {editable && (
            <form className="search" onSubmit={(e) => { e.preventDefault(); if (adding.trim()) setChooser({ line: null, term: adding.trim() }); }}>
              <Icon name="search" size={16} />
              <input type="search" placeholder="Add an item from Loblaws…" value={adding} onChange={(e) => setAdding(e.target.value)} aria-label="Add an item" />
            </form>
          )}
        </div>
        <div className="table-wrap" tabIndex={0} role="region" aria-label="Cart lines">
          <table>
            <colgroup><col className="c-thumb" /><col /><col className="c-size" /><col className="c-qty" /><col className="c-price" /><col className="c-acts" /></colgroup>
            <thead><tr><th colSpan={2}>Product</th><th>Size</th><th>Qty</th><th className="num">Price</th><th><span className="visually-hidden">Actions</span></th></tr></thead>
            <tbody>
              {shown.map((l) => (
                <Row key={l.id} l={l} editable={editable}
                  onQty={(q) => patchLine.mutate({ line: l.id, quantity: q }, { onError: onErr })}
                  onRemove={(removed) => patchLine.mutate({ line: l.id, removed }, { onError: onErr })}
                  onSwap={() => setChooser({ line: l })} />
              ))}
              {shown.length === 0 && <tr><td colSpan={6} className="none">Nothing here.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>

      <aside className="summary" aria-label="Order summary">
        {d.status === "sent" && d.pcx_cart_id ? <Handoff cartId={d.pcx_cart_id} /> : (
          <>
            <h2>Order summary</h2>
            <div className="store"><Icon name="pin" size={16} /><div><b>Loblaws</b><small>PC Express pickup · you pick a time at checkout</small></div></div>
            <div className="rows">
              <div><span>Subtotal · {live.length} lines, {items} items</span><b>{formatMoney(subtotal)}</b></div>
              {counts.removed > 0 && <div><span>Removed</span><b>{counts.removed} line{counts.removed > 1 ? "s" : ""}</b></div>}
              {counts.check > 0 && <div><span>To check</span><b>{counts.check}</b></div>}
            </div>
            <div className="total"><span>Estimated total</span><b>{formatMoney(subtotal)}</b></div>
            {can("cart.send") ? (
              <Button kind="primary" size="lg" iconAfter="external" onClick={doSend} disabled={!editable || !live.length || send.isPending}>
                {send.isPending ? "Sending…" : "Send to Loblaws"}
              </Button>
            ) : <p className="fine">Only the household's purchaser can send the cart.</p>}
            <p className="fine">Before tax. Prices can change at pickup. Nothing is ordered until you check out on loblaws.ca.</p>
            <ErrorNote error={send.error} />
            <div className="next">
              <h3>What happens next</h3>
              <ol>
                <li><i>1</i>We create the cart at Loblaws</li>
                <li><i>2</i>You load it on loblaws.ca and sign in with PC id</li>
                <li><i>3</i>Pick a pickup time and pay there</li>
              </ol>
            </div>
          </>
        )}
      </aside>

      {chooser && (
        <ProductChooser draftId={d.id} line={chooser.line} term={chooser.term} onClose={() => setChooser(null)}
          onPick={(code, qty) => {
            if (chooser.line) patchLine.mutate({ line: chooser.line.id, product_code: code }, { onError: onErr, onSuccess: () => toast({ text: "Swapped. It's remembered as your pick." }) });
            else addLine.mutate({ term: chooser.term!, product_code: code, quantity: qty }, { onError: onErr, onSuccess: () => { setAdding(""); toast({ text: "Added to the cart" }); } });
            setChooser(null);
          }} />
      )}
    </div>
  );
}

function Row({ l, editable, onQty, onRemove, onSwap }: { l: DraftLine; editable: boolean; onQty: (q: number) => void; onRemove: (r: boolean) => void; onSwap: () => void }) {
  const p = l.product;
  const need = amount(l.qty, l.unit);
  return (
    <tr className={l.removed ? "removed" : undefined}>
      <td className="thumb">{p?.image ? <img className="pth" src={p.image} alt="" loading="lazy" /> : <div className="pth empty-th"><Icon name="bag" size={18} /></div>}</td>
      <td>
        <div className="pname">{p ? [p.brand, p.name].filter(Boolean).join(" ") : l.name}</div>
        <div className="pmeta">
          <span className="pm-text" title={l.recipes.join(", ")}>{[need && `Need ${need}`, l.recipes.filter((r) => r !== "Staples").join(", ")].filter(Boolean).join(" · ") || l.name}</span>
          {l.staple && <span className="tag staple">Staple</span>}
          {l.source === "ai" && !l.removed && <span className="tag neutral">New pick</span>}
          {l.extra_id != null && <span className="tag neutral">Added</span>}
          {l.pinned && <span className="tag neutral"><Icon name="pin2" size={12} />Always</span>}
          {p?.deal_text && <span className="tag deal">{p.deal_text}</span>}
        </div>
        {!p && <div className="pnote warn"><Icon name="alert" size={14} />No match found at Loblaws.</div>}
        {(l.note || (l.needs_check && l.why)) && <div className={`pnote${l.needs_check ? " warn" : ""}`}>{l.needs_check && <Icon name="alert" size={14} />}{l.note ?? l.why}</div>}
      </td>
      <td className="size">{p?.package_size ?? ""}</td>
      <td>{l.removed || !p ? <span className="muted">—</span> :
        <Stepper value={l.quantity ?? 1} onChange={onQty} label={l.name} disabled={!editable} {...qtyRule(p)} />}</td>
      <td className="num price">
        {!l.removed && p && <><b>{formatMoney(lineCost(l))}</b><small>{p.unit_price != null ? `${formatMoney(p.unit_price)}/${p.unit_price_unit}` : formatMoney(p.price)}</small></>}
      </td>
      <td className="acts">
        {editable && (l.removed ? <Button kind="ghost" size="sm" onClick={() => onRemove(false)}>Put back</Button> : <>
          <IconButton icon="swap" label={p ? `Swap ${l.name}` : `Choose a product for ${l.name}`} className="sm" onClick={onSwap} size={17} />
          <IconButton icon="trash" label={`Remove ${l.name}`} className="sm" onClick={() => onRemove(true)} size={17} />
        </>)}
      </td>
    </tr>
  );
}
