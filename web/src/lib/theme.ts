import { useEffect, useState } from "react";

export type ThemeChoice = "light" | "dark" | "system";
const KEY = "mealprep.theme";

function apply(t: ThemeChoice) {
  if (t === "system") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.setAttribute("data-theme", t);
}

export function initTheme() {
  apply((localStorage.getItem(KEY) as ThemeChoice) || "system");
}

/** [the effective theme or "system"'s current answer, setter]. */
export function useTheme(): [ThemeChoice, (t: ThemeChoice) => void] {
  const [choice, setChoice] = useState<ThemeChoice>(() => (localStorage.getItem(KEY) as ThemeChoice) || "system");
  const [sysDark, setSysDark] = useState(() => matchMedia("(prefers-color-scheme: dark)").matches);
  useEffect(() => {
    const m = matchMedia("(prefers-color-scheme: dark)");
    const on = () => setSysDark(m.matches);
    m.addEventListener("change", on);
    return () => m.removeEventListener("change", on);
  }, []);
  const set = (t: ThemeChoice) => {
    if (t === "system") localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, t);
    apply(t);
    setChoice(t);
  };
  return [choice === "system" ? (sysDark ? "dark" : "light") : choice, set];
}
