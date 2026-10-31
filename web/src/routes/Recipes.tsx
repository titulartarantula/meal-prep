import { useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useAddToWeek, useCan, useMemberId, useRecipe, useRecipes } from "../api/queries";
import type { Recipe } from "../api/types";
import { Icon, Star } from "../components/Icon";
import { ImportDialog } from "../components/ImportDialog";
import { lastRatingText, MiniStars, PeopleChips, PeopleRatings, personName, RateRecipeDialog } from "../components/Rating";
import { EditRecipeDialog, EditSourceDialog } from "../components/RecipeEdit";
import { Button, ErrorNote, Loading, Modal, Photo, useToast } from "../components/ui";
import { amount, formatAvg, formatMinutes } from "../lib/format";
import { page, sourceLabel } from "../lib/sources";
import { DAYS_LONG, parseISO, shiftWeek, shortDate, toISO, weekName, weekNameInline, weekOf, weekRange } from "../lib/weeks";

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
  const [rating, setRating] = useState<Recipe | null>(null);

  const list = useMemo(() => {
    const norm = (s: string) => s.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();
    const words = norm(q).split(/\s+/).filter(Boolean);
    const out = (recipes.data ?? []).filter((r) => (source === "all" || r.source_kind === source) && (!company || r.ratings.good_for_company) &&
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
          {list.map((r) => <RecipeTile key={r.id} r={r} onOpen={() => navigate(`/recipes/${r.id}`)}
            onRate={can("rate.self") ? () => setRating(r) : undefined} />)}
          {list.length === 0 && <li className="none">{company && !q && source === "all" ? NO_COMPANY : "No recipes match."}</li>}
        </ul>
      )}
      <RecipeDrawer id={id ? Number(id) : null} onClose={() => navigate("/recipes")} onRate={setRating} />
      <RateRecipeDialog recipe={rating} onClose={() => setRating(null)} />
      <ImportDialog open={importing} onClose={() => setImporting(false)} onOpenRecipe={(rid) => { setImporting(false); navigate(`/recipes/${rid}`); }} />
    </main>
  );
}

const NO_COMPANY = "None rated good for company yet. After dinner, rate it and answer “Yes” to “Would you make it for company?”.";

function RecipeTile({ r, onOpen, onRate }: { r: Recipe; onOpen: () => void; onRate?: () => void }) {
  const planned = r.planned_weeks?.length ? weekName(r.planned_weeks[0]) : null;
  return (
    <li className="recipe-tile">
      <button type="button" className="tile-open" onClick={onOpen}>
        <Photo src={r.image} title={r.title} />
        <span className="tile-body">
          <b>{r.title}</b>
          <span className="meta">
            {formatMinutes(r.total_minutes) && <span><Icon name="clock" size={14} />{formatMinutes(r.total_minutes)}</span>}
            {r.ratings.avg_family ? <span><Star /><b>{formatAvg(r.ratings.avg_family)}</b><span className="visually-hidden"> family average</span></span> : <span className="muted">New</span>}
            {r.ratings.times_cooked > 0 && <span className="muted">cooked {r.ratings.times_cooked}×</span>}
            {r.ratings.good_for_company && <span className="muted">good for company</span>}
          </span>
          <PeopleChips people={r.ratings.people} />
          <span className="tile-src">{sourceText(r)}{planned && <span className="tag neutral">{planned}</span>}</span>
        </span>
      </button>
      {onRate && (
        <button type="button" className="tile-rate" onClick={onRate} aria-label={`Rate ${r.title}`} title="Rate">
          <span><Icon name="star" size={18} /></span>
        </button>
      )}
    </li>
  );
}

function sourceText(r: Recipe) {
  if (r.source_kind === "nyt") return "NYT Cooking";
  if (r.source_kind === "book") return [r.source_title ?? "Unknown book", page(r.source_ref)].filter(Boolean).join(", ");
  return r.source_title || "Other";
}

function RecipeDrawer({ id, onClose, onRate }: { id: number | null; onClose: () => void; onRate: (r: Recipe) => void }) {
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
  // the times cooked, newest first: rated dinners and past nights on the plan, and the days rated from the recipe
  const today = toISO(new Date());
  const cooked = d ? [
    ...d.history.filter((h) => h.rating_count || (h.date && h.date < today))
      .map((h) => ({ key: `p${h.entry_id}`, date: h.date ?? null, ratings: h.ratings ?? [] })),
    ...(d.rated_history ?? []).map((h) => ({ key: `r${h.date}`, date: h.date, ratings: h.ratings ?? [] })),
  ].sort((a, b) => (b.date ?? "").localeCompare(a.date ?? "")) : [];
  const me = useMemberId();
  return (
    <Modal open={id != null} onClose={onClose} drawer title={d?.title ?? "Recipe"}>
      {r.isPending ? <Loading /> : r.error ? <ErrorNote error={r.error} /> : d && (
        <div className="recipe-detail">
          <Photo src={d.image} title={d.title} alt={d.title} className="hero" />
          <div className="meta big">
            {formatMinutes(d.total_minutes) && <span><Icon name="clock" size={15} />{formatMinutes(d.total_minutes)}</span>}
            {d.servings && <span>Serves {d.servings}</span>}
            {d.ratings.avg_family ? <span><Star size={15} /><b>{formatAvg(d.ratings.avg_family)}</b> family average from {d.ratings.rating_count} {d.ratings.rating_count === 1 ? "rating" : "ratings"}</span> : <span className="muted">Not rated yet</span>}
            {d.ratings.good_for_company && <span>Good for company</span>}
            {d.source_url && <a href={d.source_url} target="_blank" rel="noreferrer">{d.source_kind === "nyt" ? "Open on NYT Cooking" : "The recipe's page"} <Icon name="external" size={13} /></a>}
          </div>
          <div className="source-line">
            <span>From: {sourceLabel(d)}</span>
            {canEdit && d.source_kind !== "nyt" && (
              <Button kind="ghost" size="sm" onClick={() => setEditing("source")}>{d.source_kind === "book" && !d.source_title ? "Add book" : "Edit source"}</Button>
            )}
            {can("rate.self") && <Button kind="outline" size="sm" icon="star" onClick={() => onRate(d)}>Rate</Button>}
            {canEdit && <Button kind="outline" size="sm" icon="edit" onClick={() => setEditing("recipe")}>Edit recipe</Button>}
          </div>
          {lastRatingText(d.ratings, null, me) && <p className="muted small">{lastRatingText(d.ratings, null, me)}</p>}
          {d.ratings.people.length > 0 && (
            <section aria-labelledby="who-rated">
              <h3 className="section-h" id="who-rated">Ratings</h3>
              <ul className="person-summary">
                {d.ratings.people.map((p) => (
                  <li key={p.member_id ?? "family"}>
                    <span className="who">{personName(p, me)}</span>
                    <MiniStars n={p.family} size={13} />
                    <span className="muted">{p.count > 1 ? `avg ${formatAvg(p.avg)} from ${p.count}` : "1 rating"}{p.date ? ` · last ${shortDate(parseISO(p.date))}` : ""}</span>
                    {p.note && <span className="pr-note">“{p.note}”</span>}
                  </li>
                ))}
              </ul>
              {d.ratings.people.some((p) => p.kind === "family") && <p className="muted small">Family = ratings from before everyone rated for themselves, and from the shared phone app.</p>}
            </section>
          )}
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
          {(d.ratings.notes.length > 0 || cooked.length > 0) && (
            <section>
              <h3 className="section-h">History</h3>
              <ul className="history">
                {cooked.slice(0, 6).map((h) => {
                  const when = h.date ? new Date(h.date + "T12:00").toLocaleDateString("en-CA", { month: "short", day: "numeric", year: "numeric" }) : "No night";
                  return (
                    <li key={h.key}>
                      <span>{when}</span>
                      {h.ratings.length ? <PeopleRatings rows={h.ratings} me={me} label={`Ratings, ${when}`} /> : <span className="muted">Not rated</span>}
                    </li>
                  );
                })}
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
