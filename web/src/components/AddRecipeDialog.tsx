import { useMemo, useState } from "react";
import { useAddToWeek, useRecipes, useWeek } from "../api/queries";
import { formatAvg, formatMinutes } from "../lib/format";
import { DAYS_LONG, shortDate, nightDate } from "../lib/weeks";
import { Icon, Star } from "./Icon";
import { ErrorNote, Loading, Modal, Photo, useToast } from "./ui";

/** Pick a library recipe for a week (onto a night, or the tray when day is null). */
export function AddRecipeDialog({ open, week, day, onClose }: { open: boolean; week: string; day: number | null; onClose: () => void }) {
  const recipes = useRecipes();
  const planned = useWeek(week).data ?? [];
  const add = useAddToWeek();
  const toast = useToast();
  const [q, setQ] = useState("");
  const [sort, setSort] = useState<"newest" | "favourites">("favourites");

  const onWeek = new Set(planned.map((e) => e.recipe_id));
  const list = useMemo(() => {
    const norm = (s: string) => s.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();
    const words = norm(q).split(/\s+/).filter(Boolean);
    const hits = (recipes.data ?? []).filter((r) => words.every((w) => norm(r.title).includes(w)));
    if (sort === "favourites") hits.sort((a, b) => (b.ratings.avg_family ?? -1) - (a.ratings.avg_family ?? -1) || b.ratings.times_cooked - a.ratings.times_cooked);
    return hits;
  }, [recipes.data, q, sort]);

  const where = day == null ? "the week (no night yet)" : `${DAYS_LONG[day]} ${shortDate(nightDate(week, day))}`;
  return (
    <Modal open={open} onClose={() => { setQ(""); onClose(); }} title={day == null ? "Add a recipe to the week" : `Dinner for ${DAYS_LONG[day]}`} wide>
      <div className="picker-bar">
        <label className="search grow">
          <Icon name="search" size={16} />
          <input autoFocus type="search" placeholder="Search your recipes" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search recipes" />
        </label>
        <div className="seg" role="group" aria-label="Sort">
          <button type="button" aria-pressed={sort === "favourites"} className={sort === "favourites" ? "on" : ""} onClick={() => setSort("favourites")}>Favourites</button>
          <button type="button" aria-pressed={sort === "newest"} className={sort === "newest" ? "on" : ""} onClick={() => setSort("newest")}>Newest</button>
        </div>
      </div>
      {recipes.isPending ? <Loading /> : (
        <ul className="pick-list" aria-label="Recipes">
          {list.map((r) => (
            <li key={r.id}>
              <button type="button" disabled={add.isPending} onClick={() => add.mutate({ week, recipeId: r.id, day }, {
                onSuccess: () => { toast({ text: `Added ${r.title} to ${where}` }); setQ(""); onClose(); },
              })}>
                <Photo src={r.image} title={r.title} className="th" />
                <span className="pick-text">
                  <b>{r.title}</b>
                  <small>
                    {[formatMinutes(r.total_minutes), r.source_kind === "book" || r.source_kind === "web" ? r.source_title : null].filter(Boolean).join(" · ")}
                  </small>
                </span>
                <span className="pick-meta">
                  {r.ratings.avg_family ? <><Star />{formatAvg(r.ratings.avg_family)}</> : <span className="muted">New</span>}
                </span>
                {onWeek.has(r.id) && <span className="tag neutral">On this week</span>}
              </button>
            </li>
          ))}
          {list.length === 0 && <li className="none">No recipes match “{q}”.</li>}
        </ul>
      )}
      <ErrorNote error={add.error} />
    </Modal>
  );
}
