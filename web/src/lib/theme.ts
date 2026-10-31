import { useEffect, useState } from "react";

export type ThemeChoice = "light" | "dark" | "system";
const KEY = "mealprep.theme";

// The browser bar / installed app's status bar colour (index.html has one per system scheme): a chosen theme wins.
const BAR = { light: "#F3F1ED", dark: "#171615" };

function apply(t: ThemeChoice) {
  if (t === "system") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.setAttribute("data-theme", t);
  document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]').forEach((m) => {
    const scheme = m.media.includes("dark") ? "dark" : "light";
    m.content = BAR[t === "system" ? scheme : t];
  });
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
