import { useSyncExternalStore } from "react";

/** Phones and portrait tablets: bottom tab bar, one column, full-screen sheets. Keep in step with app.css. */
export const COMPACT = "(max-width: 899px)";

export function useMedia(query: string): boolean {
  return useSyncExternalStore(
    (on) => { const m = matchMedia(query); m.addEventListener("change", on); return () => m.removeEventListener("change", on); },
    () => matchMedia(query).matches,
  );
}

export const useCompact = () => useMedia(COMPACT);
