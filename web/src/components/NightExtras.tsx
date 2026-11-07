// A night beyond its recipes (2026-11-06): an optional name ("Pizza night") and store items added for that night with
// the product chooser. The items go into the week's cart like any added item (POST /extras with the day).
import { useEffect, useState } from "react";
import type { Extra } from "../api/types";
import { qtyRule } from "../api/types";
import { DAYS, DAYS_LONG } from "../lib/weeks";
import { Icon } from "./Icon";
import { Button, Menu, MenuItem, Modal } from "./ui";

export const NIGHT_TITLE_MAX = 60;

/** "Delissio Pepperoni Pizza" (brand + product), else what was searched. */
export function itemName(e: Pick<Extra, "product" | "term">): string {
  const p = e.product;
  return p ? [p.brand, p.name].filter(Boolean).join(" ") : e.term;
}

/** "×2", or grams for a weighed product ("300 g"). */
export function itemQty(e: Pick<Extra, "product" | "quantity">): string {
  return e.product?.weighted ? qtyRule(e.product).format(e.quantity) : `×${e.quantity}`;
}

/** One line for screen readers and the collapsed view: "Delissio Pizza ×2 · Caesar Salad Kit". */
export function storeSummary(items: Extra[]): string {
  return items.map((e) => (e.quantity > 1 || e.product?.weighted ? `${itemName(e)} ${itemQty(e)}` : itemName(e))).join(" · ");
}

/** The night's store items: each with a ⋮ menu (one more / one less, move to another night or the whole week,
 * remove), and "Add items" for purchasers. */
export function NightStore({ day, items, canEdit, onAdd, onQty, onMove, onRemove }: {
  day: number; items: Extra[]; canEdit: boolean; onAdd: () => void;
  onQty: (e: Extra, quantity: number) => void; onMove: (e: Extra, day: number | null) => void; onRemove: (e: Extra) => void;
}) {
  if (!items.length && !canEdit) return null;
  return (
    <div className="night-store" aria-label={`From the store, ${DAYS_LONG[day]}`} role="group">
      {items.length > 0 && (
        <>
          <div className="ns-label"><Icon name="bag" size={13} />From the store</div>
          <ul className="ns-items">
            {items.map((e) => {
              const name = itemName(e);
              const rule = qtyRule(e.product);
              return (
                <li key={e.id}>
                  <span className="ns-name" title={name}>{name}</span>
                  {(e.quantity > 1 || e.product?.weighted) && <span className="ns-qty">{itemQty(e)}</span>}
                  {canEdit && (
                    <Menu label={`Options for ${name}`}>
                      {(close) => (
                        <>
                          <div className="menu-label">Quantity: {rule.format(e.quantity)}</div>
                          {e.quantity + rule.step <= rule.max && (
                            <MenuItem icon="plus" onSelect={() => { close(); onQty(e, e.quantity + rule.step); }}>Add one more</MenuItem>
                          )}
                          {e.quantity - rule.step >= rule.min && (
                            <MenuItem icon="minus" onSelect={() => { close(); onQty(e, e.quantity - rule.step); }}>One less</MenuItem>
                          )}
                          <div className="menu-label">Move to</div>
                          <div className="menu-days">
                            {DAYS.map((dn, d) => (
                              <button key={d} type="button" role="menuitem" aria-current={e.day === d} disabled={e.day === d}
                                aria-label={DAYS_LONG[d]} onClick={() => { close(); onMove(e, d); }}>{dn}</button>
                            ))}
                          </div>
                          <MenuItem icon="inbox" onSelect={() => { close(); onMove(e, null); }}>For the whole week</MenuItem>
                          <MenuItem icon="trash" danger onSelect={() => { close(); onRemove(e); }}>Remove</MenuItem>
                        </>
                      )}
                    </Menu>
                  )}
                </li>
              );
            })}
          </ul>
        </>
      )}
      {canEdit && (
        <button type="button" className="link-btn ns-add" onClick={onAdd}>
          <Icon name="plus" size={14} />{items.length ? "Add more items" : "Add items"}
        </button>
      )}
    </div>
  );
}

/** Name, rename or clear a night. An empty name clears it. */
export function NameNightDialog({ day, title, onSave, onClose }: {
  day: number | null; title: string | null; onSave: (title: string) => void; onClose: () => void;
}) {
  const [text, setText] = useState(title ?? "");
  useEffect(() => setText(title ?? ""), [day, title]);
  const clean = text.trim().replace(/\s+/g, " ");
  const tooLong = clean.length > NIGHT_TITLE_MAX;
  const save = () => { if (!tooLong) { onSave(clean); onClose(); } };
  return (
    <Modal open={day != null} onClose={onClose} title={day == null ? "" : title ? `Rename ${DAYS_LONG[day]}` : `Name ${DAYS_LONG[day]}`}
      footer={
        <>
          {title && <Button kind="ghost" onClick={() => { onSave(""); onClose(); }}>Clear name</Button>}
          <Button kind="outline" onClick={onClose}>Cancel</Button>
          <Button kind="primary" onClick={save} disabled={tooLong}>Save</Button>
        </>
      }>
      <form onSubmit={(e) => { e.preventDefault(); save(); }}>
        <label className="field">
          <span>Name of the night</span>
          <input type="text" value={text} autoFocus maxLength={NIGHT_TITLE_MAX + 20} placeholder="Pizza night, Leftovers, Out for dinner…"
            onChange={(e) => setText(e.target.value)} aria-invalid={tooLong} aria-describedby="night-name-help" />
        </label>
        <p id="night-name-help" className={`small ${tooLong ? "error-text" : "muted"}`}>
          {tooLong ? `At most ${NIGHT_TITLE_MAX} characters (${clean.length} now).` : "Optional. Leave it empty to remove the name."}
        </p>
      </form>
    </Modal>
  );
}
