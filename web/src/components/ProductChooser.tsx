import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { lineChoices, productChoices } from "../api/queries";
import type { Choice, DraftLine } from "../api/types";
import { formatMoney } from "../lib/format";
import { Icon } from "./Icon";
import { Button, ErrorNote, Loading, Modal } from "./ui";

const GROUPS: Record<string, string> = { current: "In your cart", bought: "Bought before", deal: "On sale", other: "More results", out: "Out of stock" };

/** Swap a line's product (line set) or add an item by search (line null; term = the first search, or none to start
 * with an empty search box). onPick gets the search the product was found with. `title` overrides the heading;
 * `status` is announced after a pick when the chooser stays open (adding several items to a night). */
export function ProductChooser({ draftId = null, line, term, title, status, onClose, onPick }: {
  draftId?: number | null; line: DraftLine | null; term?: string; title?: string; status?: string | null;
  onClose: () => void; onPick: (code: string, quantity: number, term: string) => void;
}) {
  const [q, setQ] = useState(term ?? "");
  const [search, setSearch] = useState<string | null>(term ?? null);
  const choices = useQuery({
    queryKey: ["choices", draftId, line?.id ?? null, search],
    queryFn: () => (line && draftId != null ? lineChoices(draftId, line.id, search) : productChoices(search!)),
    enabled: !!line || !!search,
  });
  const groups = new Map<string, Choice[]>();
  for (const c of choices.data?.choices ?? []) groups.set(c.group, [...(groups.get(c.group) ?? []), c]);

  return (
    <Modal open onClose={onClose} drawer title={title ?? (line ? `Choose: ${line.name}` : `Add: ${term}`)}
      footer={status !== undefined ? <Button kind="primary" onClick={onClose}>Done</Button> : undefined}>
      <form className="search full" onSubmit={(e) => { e.preventDefault(); setSearch(q.trim() || null); }}>
        <Icon name="search" size={16} />
        <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search Loblaws" aria-label="Search Loblaws"
          autoFocus={!term && !line} />
        <Button size="sm" kind="outline" type="submit">Search</Button>
      </form>
      {status !== undefined && <p className="chooser-status" role="status">{status ?? ""}</p>}
      {line && (line.qty != null) && <p className="muted small">The recipes need {[line.qty, line.unit].filter(Boolean).join(" ")}.</p>}
      {!line && !search ? <p className="muted small">Search for a product, like “frozen pizza” or “caesar salad kit”.</p>
        : choices.isPending ? <Loading label="Searching Loblaws…" /> : choices.error ? <ErrorNote error={choices.error} onRetry={() => choices.refetch()} /> : (
        [...groups.entries()].map(([g, cs]) => (
          <section key={g} className="choice-group">
            <h3 className="section-h">{GROUPS[g] ?? g}</h3>
            <ul className="choices">
              {cs.map((c) => {
                const p = c.product;
                return (
                  <li key={p.code}>
                    <button type="button" className={c.current ? "current" : undefined} disabled={g === "out" || c.current}
                      onClick={() => onPick(p.code, c.suggested_quantity || 1, search ?? term ?? "")}>
                      {p.image ? <img className="pth" src={p.image} alt="" loading="lazy" /> : <div className="pth empty-th" />}
                      <span className="c-text">
                        <b>{[p.brand, p.name].filter(Boolean).join(" ")}</b>
                        <small>{[p.package_size, p.unit_price != null && `${formatMoney(p.unit_price)}/${p.unit_price_unit}`].filter(Boolean).join(" · ")}</small>
                        <span className="c-tags">
                          {c.current && <span className="tag neutral">Current</span>}
                          {p.deal_text && <span className="tag deal">{p.deal_text}</span>}
                          {c.last_bought?.date && <span className="tag neutral">Bought before</span>}
                          {c.cover && <span className="muted small">{c.cover.packs} × covers it · {formatMoney(c.cover.cost)}</span>}
                        </span>
                      </span>
                      <span className="c-price">{formatMoney(p.price)}{p.was_price ? <s>{formatMoney(p.was_price)}</s> : null}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </section>
        ))
      )}
      {choices.data && choices.data.choices.length === 0 && <p className="none">Nothing found. Try another word.</p>}
    </Modal>
  );
}
