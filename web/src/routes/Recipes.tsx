import { useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useAddToWeek, useCan, useRecipe, useRecipes } from "../api/queries";
import type { Recipe } from "../api/types";
import { Icon, Star } from "../components/Icon";
import { ImportDialog } from "../components/ImportDialog";
import { EditRecipeDialog, EditSourceDialog } from "../components/RecipeEdit";
import { Button, ErrorNote, Loading, Modal, Photo, useToast } from "../components/ui";
import { amount, formatAvg, formatMinutes } from "../lib/format";
import { page, sourceLabel } from "../lib/sources";
import { DAYS_LONG, shiftWeek, weekName, weekNameInline, weekOf, weekRange } from "../lib/weeks";

type Sort = "newest" | "favourites" | "az";

export function Recipes() {
  const { id } = useParams();
  const navigate = useNavigate();
  const recipes = useRecipes();
  const can = useCan();
  const [q, setQ] = useState("");
  const [sort, setSort] = useState<Sort>("newest");
  const [source, setSource] = useState<"all" | "nyt" | "book" | "other">("all");
  const [importing, setImporting] = useState(false);
  const [company, setCompany] = useState(false);

  const list = useMemo(() => {
    const norm = (s: string) => s.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();
    const words = norm(q).split(/\s+/).filter(Boolean);
    const out = (recipes.data ?? []).filter((r) => (source === "all" || r.source_kind === source) && (!company || r.ratings.company === "yes") &&
      words.every((w) => norm(r.title + " " + (r.source_title ?? "")).includes(w)));
    if (sort === "favourites") out.sort((a, b) => (b.ratings.avg_family ?? -1) - (a.ratings.avg_family ?? -1) || b.ratings.times_cooked - a.ratings.times_cooked);
    if (sort === "az") out.sort((a, b) => a.title.localeCompare(b.title));
    return out;
  }, [recipes.data, q, sort, source, company]);

  return (
    <main className="main scroll" id="main">
      <div className="top">
        <div>
          <div className="eyebrow">Library</div>
          <h1>Recipes</h1>
          <div className="sub">{recipes.data ? `${recipes.data.length} recipes` : " "}</div>
        </div>
        {can("recipe.edit") && <Button kind="primary" icon="plus" onClick={() => setImporting(true)}>Add recipe</Button>}
      </div>
      <div className="toolbar">
        <label className="search">
          <Icon name="search" size={16} />
          <input type="search" placeholder="Search recipes or books" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search recipes" />
        </label>
        <div className="seg" role="group" aria-label="Source">
          {(["all", "nyt", "book", "other"] as const).map((s) => (
            <button key={s} type="button" className={source === s ? "on" : ""} aria-pressed={source === s} onClick={() => setSource(s)}>
              {{ all: "All", nyt: "NYT Cooking", book: "Books", other: "Other" }[s]}
            </button>
          ))}
        </div>
        <button type="button" className={`toggle${company ? " on" : ""}`} aria-pressed={company} onClick={() => setCompany(!company)}>
          {company && <Icon name="check" size={15} />}Good for company
        </button>
        <label className="select-wrap">
          <span className="visually-hidden">Sort</span>
          <select value={sort} onChange={(e) => setSort(e.target.value as Sort)}>
            <option value="newest">Newest first</option>
            <option value="favourites">Favourites first</option>
            <option value="az">A–Z</option>
          </select>
          <Icon name="chevdown" size={16} />
        </label>
      </div>
      {recipes.isPending ? <Loading /> : recipes.error ? <ErrorNote error={recipes.error} onRetry={() => recipes.refetch()} /> : (
        <ul className="recipe-grid" aria-label="Recipes">
          {list.map((r) => <RecipeTile key={r.id} r={r} onOpen={() => navigate(`/recipes/${r.id}`)} />)}
          {list.length === 0 && <li className="none">{company && !q && source === "all" ? NO_COMPANY : "No recipes match."}</li>}
        </ul>
      )}
      <RecipeDrawer id={id ? Number(id) : null} onClose={() => navigate("/recipes")} />
      <ImportDialog open={importing} onClose={() => setImporting(false)} onOpenRecipe={(rid) => { setImporting(false); navigate(`/recipes/${rid}`); }} />
    </main>
  );
}

const NO_COMPANY = "None rated good for company yet. After dinner, rate it and answer “Yes” to “Would you make it for company?”.";

function RecipeTile({ r, onOpen }: { r: Recipe; onOpen: () => void }) {
  const planned = r.planned_weeks?.length ? weekName(r.planned_weeks[0]) : null;
  return (
    <li className="recipe-tile">
      <button type="button" onClick={onOpen}>
        <Photo src={r.image} title={r.title} />
        <span className="tile-body">
          <b>{r.title}</b>
          <span className="meta">
            {formatMinutes(r.total_minutes) && <span><Icon name="clock" size={14} />{formatMinutes(r.total_minutes)}</span>}
            {r.ratings.avg_family ? <span><Star /><b>{formatAvg(r.ratings.avg_family)}</b></span> : <span className="muted">New</span>}
            {r.ratings.times_cooked > 0 && <span className="muted">cooked {r.ratings.times_cooked}×</span>}
            {r.ratings.company === "yes" && <span className="muted">good for company</span>}
          </span>
          <span className="tile-src">{sourceText(r)}{planned && <span className="tag neutral">{planned}</span>}</span>
        </span>
      </button>
    </li>
  );
}

function sourceText(r: Recipe) {
  if (r.source_kind === "nyt") return "NYT Cooking";
  if (r.source_kind === "book") return [r.source_title ?? "Unknown book", page(r.source_ref)].filter(Boolean).join(", ");
  return r.source_title || "Other";
}

function RecipeDrawer({ id, onClose }: { id: number | null; onClose: () => void }) {
  const r = useRecipe(id);
  const can = useCan();
  const add = useAddToWeek();
  const toast = useToast();
  const thisWeek = weekOf(new Date());
  const [week, setWeek] = useState(shiftWeek(thisWeek, 1));
  const [day, setDay] = useState<string>("");
  const [editing, setEditing] = useState<"recipe" | "source" | null>(null);
  const d = r.data;
  const canEdit = can("recipe.edit");
  return (
    <Modal open={id != null} onClose={onClose} drawer title={d?.title ?? "Recipe"}>
      {r.isPending ? <Loading /> : r.error ? <ErrorNote error={r.error} /> : d && (
        <div className="recipe-detail">
          <Photo src={d.image} title={d.title} alt={d.title} className="hero" />
          <div className="meta big">
            {formatMinutes(d.total_minutes) && <span><Icon name="clock" size={15} />{formatMinutes(d.total_minutes)}</span>}
            {d.servings && <span>Serves {d.servings}</span>}
            {d.ratings.avg_family ? <span><Star size={15} /><b>{formatAvg(d.ratings.avg_family)}</b> from {d.ratings.times_rated}</span> : <span className="muted">Not rated yet</span>}
            {d.source_url && <a href={d.source_url} target="_blank" rel="noreferrer">{d.source_kind === "nyt" ? "Open on NYT Cooking" : "The recipe's page"} <Icon name="external" size={13} /></a>}
          </div>
          <div className="source-line">
            <span>From: {sourceLabel(d)}</span>
            {canEdit && d.source_kind !== "nyt" && (
              <Button kind="ghost" size="sm" onClick={() => setEditing("source")}>{d.source_kind === "book" && !d.source_title ? "Add book" : "Edit source"}</Button>
            )}
            {canEdit && <Button kind="outline" size="sm" icon="edit" onClick={() => setEditing("recipe")}>Edit recipe</Button>}
          </div>
          {d.description && <p>{d.description}</p>}

          {can("plan.edit") && (
            <div className="add-week">
              <label className="select-wrap">
                <span className="visually-hidden">Week</span>
                <select value={week} onChange={(e) => setWeek(e.target.value)}>
                  {[0, 1, 2, 3, 4].map((n) => { const w = shiftWeek(thisWeek, n); return <option key={w} value={w}>{weekName(w)} · {weekRange(w)}</option>; })}
                </select>
                <Icon name="chevdown" size={16} />
              </label>
              <label className="select-wrap">
                <span className="visually-hidden">Night</span>
                <select value={day} onChange={(e) => setDay(e.target.value)}>
                  <option value="">No night yet</option>
                  {DAYS_LONG.map((n, i) => <option key={i} value={i}>{n}</option>)}
                </select>
                <Icon name="chevdown" size={16} />
              </label>
              <Button kind="primary" icon="plus" disabled={add.isPending}
                onClick={() => add.mutate({ week, recipeId: d.id, day: day === "" ? null : Number(day) },
                  { onSuccess: () => toast({ text: `Added to ${weekNameInline(week)}` }) })}>Add to week</Button>
            </div>
          )}
          {d.planned_weeks.length > 0 && <p className="muted small">On the plan: {d.planned_weeks.map((w) => weekName(w)).join(", ")}</p>}

          <div className="detail-cols">
            <section>
              <h3 className="section-h">Ingredients</h3>
              <ul className="ings">
                {d.ingredients.map((i, n) => (
                  <li key={n}><span className="amt">{amount(i.qty, i.unit)}</span><span>{i.name}{i.prep ? <span className="muted">, {i.prep}</span> : null}</span></li>
                ))}
              </ul>
            </section>
            <section>
              <h3 className="section-h">Steps</h3>
              <ol className="steps-list">{d.steps.map((s, n) => <li key={n}>{s}</li>)}</ol>
            </section>
          </div>
          {(d.ratings.notes.length > 0 || d.history.length > 0) && (
            <section>
              <h3 className="section-h">History</h3>
              <ul className="history">
                {(d.history as { entry_id: number; date?: string | null; rating?: { family: number; note?: string | null } | null }[]).slice(-6).reverse().map((h) => (
                  <li key={h.entry_id}>
                    <span>{h.date ? new Date(h.date + "T12:00").toLocaleDateString("en-CA", { month: "short", day: "numeric", year: "numeric" }) : "No night"}</span>
                    <span>{h.rating ? <>{[1, 2, 3, 4, 5].map((n) => <Star key={n} filled={n <= h.rating!.family} />)}{h.rating.note && <span className="muted"> “{h.rating.note}”</span>}</> : <span className="muted">Not rated</span>}</span>
                  </li>
                ))}
              </ul>
            </section>
          )}
          {d.notes && <section><h3 className="section-h">Notes</h3><p className="pre-line">{d.notes}</p></section>}
          {editing === "recipe" && <EditRecipeDialog recipe={d} onClose={() => setEditing(null)} />}
          {editing === "source" && <EditSourceDialog recipe={d} onClose={() => setEditing(null)} />}
        </div>
      )}
    </Modal>
  );
}
