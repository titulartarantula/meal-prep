const money = new Intl.NumberFormat("en-CA", { style: "currency", currency: "CAD" });

export function formatMoney(n: number | null | undefined): string {
  return n == null ? "—" : money.format(n);
}

/** "35 min", "1 h 15", "2 h". */
export function formatMinutes(m: number | null | undefined): string | null {
  if (!m) return null;
  if (m < 60) return `${m} min`;
  const h = Math.floor(m / 60), r = m % 60;
  return r ? `${h} h ${String(r).padStart(2, "0")}` : `${h} h`;
}

/** 4.75 → "4.8"; null → null. */
export function formatAvg(n: number | null | undefined): string | null {
  return n == null ? null : n.toFixed(1);
}

const FRACTIONS: [number, string][] = [[1 / 8, "⅛"], [1 / 4, "¼"], [1 / 3, "⅓"], [3 / 8, "⅜"], [1 / 2, "½"],
  [5 / 8, "⅝"], [2 / 3, "⅔"], [3 / 4, "¾"], [7 / 8, "⅞"]];

/** 1.5 → "1½", 0.333 → "⅓", 2 → "2", 1.37 → "1.37". */
export function formatQty(q: number | null | undefined): string {
  if (q == null) return "";
  const whole = Math.floor(q), frac = q - whole;
  if (frac < 0.02) return String(whole);
  const hit = FRACTIONS.find(([v]) => Math.abs(v - frac) < 0.02);
  if (hit) return whole ? `${whole}${hit[1]}` : hit[1];
  return String(Math.round(q * 100) / 100);
}

export function amount(qty: number | null | undefined, unit: string | null | undefined): string {
  return [formatQty(qty), unit ?? ""].filter(Boolean).join(" ");
}

/** "2 h ago", "Yesterday", "Tue", "Oct 12". */
export function ago(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return "";
  const t = new Date(iso);
  const s = (now.getTime() - t.getTime()) / 1000;
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400 && t.getDate() === now.getDate()) return `${Math.floor(s / 3600)} h ago`;
  if (s < 2 * 86400) return "Yesterday";
  if (s < 6 * 86400) return t.toLocaleDateString("en-CA", { weekday: "short" });
  return t.toLocaleDateString("en-CA", { month: "short", day: "numeric" });
}

/** ago() inside a sentence: "sent 2 h ago", "sent yesterday", "sent Oct 12". */
export function agoInline(iso: string | null | undefined, now: Date = new Date()): string {
  const a = ago(iso, now);
  return a === "Yesterday" ? "yesterday" : a;
}

export function initials(name: string | null | undefined): string {
  return (name ?? "?").trim().split(/\s+/).map((w) => w[0]).slice(0, 2).join("").toUpperCase() || "?";
}
