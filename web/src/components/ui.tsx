// Small shared pieces: buttons, dialogs/drawers (native <dialog>: focus trap, Esc), photos, stars, stepper, toasts.
import { createContext, useCallback, useContext, useEffect, useId, useRef, useState, type ReactNode } from "react";
import { Icon, Star } from "./Icon";
import type { IconName } from "./iconPaths";

export function Button({ kind = "quiet", size, icon, iconAfter, children, className, ...rest }:
  React.ButtonHTMLAttributes<HTMLButtonElement> & { kind?: "primary" | "quiet" | "ghost" | "outline" | "danger";
    size?: "sm" | "lg"; icon?: IconName; iconAfter?: IconName }) {
  return (
    <button type="button" className={["btn", kind, size, className].filter(Boolean).join(" ")} {...rest}>
      {icon && <Icon name={icon} size={size === "sm" ? 16 : 17} />}
      {children}
      {iconAfter && <Icon name={iconAfter} size={size === "sm" ? 16 : 17} />}
    </button>
  );
}

export function IconButton({ icon, label, size = 18, className, ...rest }:
  React.ButtonHTMLAttributes<HTMLButtonElement> & { icon: IconName; label: string; size?: number }) {
  return (
    <button type="button" className={["iconbtn", className].filter(Boolean).join(" ")} aria-label={label} title={label} {...rest}>
      <Icon name={icon} size={size} />
    </button>
  );
}

/** A modal (centred) or drawer (right side) on a native <dialog>. Closing (Esc, scrim click, ×) calls onClose. */
export function Modal({ open, onClose, title, children, footer, drawer, wide, labelledBy }: {
  open: boolean; onClose: () => void; title?: ReactNode; children: ReactNode; footer?: ReactNode; drawer?: boolean;
  wide?: boolean; labelledBy?: string;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const id = useId();
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);
  return (
    <dialog ref={ref} className={["modal", drawer && "drawer", wide && "wide"].filter(Boolean).join(" ")}
      aria-labelledby={labelledBy ?? (title ? id : undefined)}
      onCancel={(e) => { e.preventDefault(); onClose(); }}
      onClick={(e) => { if (e.target === ref.current) onClose(); }}>
      {open && (
        <div className="modal-box">
          {title && (
            <header className="modal-head">
              <h2 id={id}>{title}</h2>
              <IconButton icon="x" label="Close" onClick={onClose} />
            </header>
          )}
          <div className="modal-body">{children}</div>
          {footer && <footer className="modal-foot">{footer}</footer>}
        </div>
      )}
    </dialog>
  );
}

const TINTS = ["#D9CDBA", "#CFD1BC", "#DACBBF", "#D4CDB8", "#DDD1B5", "#D2C7B9"];

/** A recipe's photo, or a calm tonal tile (most NYT shares have no image). */
export function Photo({ src, title, className }: { src?: string | null; title: string; className?: string }) {
  const [broken, setBroken] = useState(false);
  if (src && !broken) {
    return <img className={["photo", className].filter(Boolean).join(" ")} src={src} alt="" loading="lazy" onError={() => setBroken(true)} />;
  }
  const h = [...title].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7);
  return (
    <div className={["photo", "placeholder", className].filter(Boolean).join(" ")} style={{ ["--tint" as string]: TINTS[h % TINTS.length] }}
      aria-hidden="true">
      <Icon name="dish" size={30} />
    </div>
  );
}

export function StarsInput({ value, onChange, label }: { value: number; onChange: (n: number) => void; label: string }) {
  const [hover, setHover] = useState(0);
  return (
    <div className="stars-input" role="radiogroup" aria-label={label} onMouseLeave={() => setHover(0)}>
      {[1, 2, 3, 4, 5].map((n) => (
        <button key={n} type="button" role="radio" aria-checked={value === n} aria-label={`${n} star${n > 1 ? "s" : ""}`}
          className={(hover || value) >= n ? "on" : ""} onMouseEnter={() => setHover(n)} onClick={() => onChange(n)}>
          <Star size={26} filled={(hover || value) >= n} />
        </button>
      ))}
    </div>
  );
}

export function Stepper({ value, min = 1, max = 99, step = 1, format = String, onChange, label, disabled }: {
  value: number; min?: number; max?: number; step?: number; format?: (n: number) => string; onChange: (n: number) => void;
  label: string; disabled?: boolean;
}) {
  const by = step > 1 ? ` (${format(step)})` : "";
  return (
    <div className={`qty${step > 1 ? " wide" : ""}`} role="group" aria-label={`Quantity of ${label}`}>
      <button type="button" aria-label={`Fewer ${label}${by}`} disabled={disabled || value - step < min} onClick={() => onChange(value - step)}>
        <Icon name="minus" size={15} />
      </button>
      <output aria-live="polite">{format(value)}</output>
      <button type="button" aria-label={`More ${label}${by}`} disabled={disabled || value + step > max} onClick={() => onChange(value + step)}>
        <Icon name="plus" size={15} />
      </button>
    </div>
  );
}

export function Spinner({ label = "Loading" }: { label?: string }) {
  return <span className="spinner" role="status" aria-label={label} />;
}

export function Loading({ label = "Loading…" }: { label?: string }) {
  return <div className="loading"><Spinner label={label} /><span>{label}</span></div>;
}

export function ErrorNote({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  if (!error) return null;
  const msg = error instanceof Error ? error.message : String(error);
  return (
    <div className="note error" role="alert">
      <Icon name="alert" size={17} />
      <span>{msg}</span>
      {onRetry && <Button size="sm" kind="ghost" onClick={onRetry}>Try again</Button>}
    </div>
  );
}

type Toast = { id: number; text: string; action?: { label: string; run: () => void } };
const ToastCtx = createContext<(t: Omit<Toast, "id">) => void>(() => {});
export const useToast = () => useContext(ToastCtx);

export function ToastHost({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push = useCallback((t: Omit<Toast, "id">) => {
    const id = Date.now() + Math.random();
    setToasts((ts) => [...ts.slice(-2), { ...t, id }]);
    setTimeout(() => setToasts((ts) => ts.filter((x) => x.id !== id)), 5000);
  }, []);
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className="toast">
            <span>{t.text}</span>
            {t.action && <button type="button" onClick={() => { t.action!.run(); setToasts((ts) => ts.filter((x) => x.id !== t.id)); }}>{t.action.label}</button>}
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

/** A small popover menu anchored to a button (closes on outside click / Esc). */
export function Menu({ label, icon = "more", children, align = "right" }:
  { label: string; icon?: IconName; children: (close: () => void) => ReactNode; align?: "left" | "right" }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const off = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false); };
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("mousedown", off);
    document.addEventListener("keydown", esc);
    return () => { document.removeEventListener("mousedown", off); document.removeEventListener("keydown", esc); };
  }, [open]);
  return (
    <div className="menu-wrap" ref={ref}>
      <IconButton icon={icon} label={label} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)} className="sm" />
      {open && <div className={`menu ${align}`} role="menu">{children(() => setOpen(false))}</div>}
    </div>
  );
}

export function MenuItem({ icon, children, onSelect, danger }: { icon?: IconName; children: ReactNode; onSelect: () => void; danger?: boolean }) {
  return (
    <button type="button" role="menuitem" className={danger ? "danger" : undefined} onClick={onSelect}>
      {icon && <Icon name={icon} size={16} />}
      {children}
    </button>
  );
}
