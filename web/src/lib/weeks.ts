// Weeks run Monday–Sunday and are named by their Monday as an ISO date ("2026-11-16"), like the server (Sunday–Saturday
// before 2026-11-14). A night's day is its offset from the Monday: 0 = Mon … 6 = Sun.
// Dates are handled as local calendar days (no time zone shifts: "2026-11-01" is never parsed as UTC midnight).

export const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] as const;
export const DAYS_LONG = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"] as const;
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"] as const;

export function parseISO(s: string): Date {
  const [y, m, d] = s.slice(0, 10).split("-").map(Number);
  return new Date(y, m - 1, d);
}

export function toISO(d: Date): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function addDays(d: Date, n: number): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
}

/** The Monday that starts the week holding d. */
export function weekOf(d: Date): string {
  return toISO(addDays(d, -((d.getDay() + 6) % 7)));
}

export function shiftWeek(week: string, n: number): string {
  return toISO(addDays(parseISO(week), 7 * n));
}

/** The date of a night (0 = Monday) in a week. */
export function nightDate(week: string, day: number): Date {
  return addDays(parseISO(week), day);
}

export function shortDate(d: Date): string {
  return `${MONTHS[d.getMonth()]} ${d.getDate()}`;
}

/** "Oct 25 – 31", or "Oct 25 – Nov 1" across a month. */
export function weekRange(week: string): string {
  const a = parseISO(week), b = addDays(a, 6);
  return a.getMonth() === b.getMonth() ? `${shortDate(a)} – ${b.getDate()}` : `${shortDate(a)} – ${shortDate(b)}`;
}

/** "This week", "Next week", "Last week", else "Week of Nov 8". */
export function weekName(week: string, today: Date = new Date()): string {
  const n = Math.round((parseISO(week).getTime() - parseISO(weekOf(today)).getTime()) / (7 * 864e5));
  if (n === 0) return "This week";
  if (n === 1) return "Next week";
  if (n === -1) return "Last week";
  return `Week of ${shortDate(parseISO(week))}`;
}

export function isSameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

/** weekName() inside a sentence: "next week", "the week of Nov 8". */
export function weekNameInline(week: string, today: Date = new Date()): string {
  const n = weekName(week, today);
  return n.startsWith("Week of") ? `the w${n.slice(1)}` : n.toLowerCase();
}
