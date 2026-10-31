import { useMemo, useState, type DragEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  useCan, useMemberId, useMoveEntry, useRecipes, useRemoveEntry, useWeek, useWeekDraft, useWeeks,
} from "../api/queries";
import type { PlanEntry, Recipe } from "../api/types";
import { Icon, Star } from "../components/Icon";
import { AddRecipeDialog } from "../components/AddRecipeDialog";
import { MiniStars, PeopleRatings, RateDialog, RatingBanner, pendingEntry, usePendingBanner } from "../components/Rating";
import { Button, ErrorNote, IconButton, Loading, Menu, MenuItem, Photo, useToast } from "../components/ui";
import { formatAvg, formatMinutes } from "../lib/format";
import { useCompact } from "../lib/media";
import { DAYS, DAYS_LONG, isSameDay, nightDate, shiftWeek, shortDate, weekName, weekOf, weekRange } from "../lib/weeks";

const DRAG = "application/x-mealprep-entry";

export function ThisWeek() {
  const params = useParams();
  const thisWeek = weekOf(new Date());
  const week = params.week ?? thisWeek;
  const navigate = useNavigate();
  const can = useCan();
  const entries = useWeek(week);
  const recipes = useRecipes();
  const summary = useWeeks(week, 1).data?.[0];
  const draft = useWeekDraft(week).data;
  const move = useMoveEntry(week);
  const remove = useRemoveEntry(week);
  const toast = useToast();
  const [adding, setAdding] = useState<number | null | "tray">(null);
  const [rating, setRating] = useState<PlanEntry | null>(null);
  const [dragOver, setDragOver] = useState<number | "tray" | null>(null);

  const byId = useMemo(() => new Map((recipes.data ?? []).map((r) => [r.id, r])), [recipes.data]);
  const list = entries.data ?? [];
  const nights = DAYS.map((_, d) => list.filter((e) => e.day === d));
  const tray = list.filter((e) => e.day == null);
  const today = new Date();
  const editable = can("plan.edit");
  // Touch layouts place dinners with the ⋮ menu: HTML drag-and-drop doesn't start from a finger.
  const compact = useCompact();
  const drag = editable && !compact;
  const canRate = can("rate.self");
  const me = useMemberId();
  const [pending, rateLater] = usePendingBanner(canRate);

  const go = (w: string) => navigate(w === thisWeek ? "/week" : `/week/${w}`);

  function onDrop(e: DragEvent, day: number | null) {
    e.preventDefault();
    setDragOver(null);
    const id = Number(e.dataTransfer.getData(DRAG));
    const entry = list.find((x) => x.id === id);
    if (!entry || entry.day === day) return;
    move.mutate({ id, day }, { onError: (err) => toast({ text: `Couldn't move it: ${err.message}` }) });
  }
  const dropProps = (day: number | null) => drag ? {
    onDragOver: (e: DragEvent) => { if (e.dataTransfer.types.includes(DRAG)) { e.preventDefault(); setDragOver(day ?? "tray"); } },
    onDragLeave: () => setDragOver(null),
    onDrop: (e: DragEvent) => onDrop(e, day),
  } : {};

  function removeEntry(e: PlanEntry) {
    remove.mutate(e.id, { onSuccess: () => toast({ text: `Removed ${e.title ?? "the recipe"} from the week` }) });
  }

  const cartStep = summary?.carted ? "done" : summary?.cart_stale ? "stale" : "todo";
  // The week's one main action; on a phone it is docked above the tab bar, like the Android app's.
  const size = compact ? "lg" : undefined;
  const action = list.length === 0 ? (
    editable ? <Button kind="primary" size={size} icon="plus" onClick={() => setAdding("tray")}>Add recipes</Button> : null
  ) : summary?.carted && draft ? (
    <Button kind="outline" size={size} iconAfter="arrow" onClick={() => navigate(`/cart/${draft.id}`)}>View cart</Button>
  ) : can("cart.build") ? (
    <Button kind="primary" size={size} iconAfter="arrow" onClick={() => navigate(`/shopping?week=${week}`)}>
      {draft && draft.status !== "sent" && !draft.stale ? "Review cart" : "Build cart"}
    </Button>
  ) : null;
  const placed = list.filter((e) => e.day != null).length;

  return (
    <main className="main" id="main">
      <div className="top">
        <div>
          <div className="eyebrow">{shortDate(nightDate(week, 0))} – {shortDate(nightDate(week, 6))}</div>
          <div className="title-row">
            <h1>{weekName(week)}</h1>
            <span className="weeknav">
              <IconButton icon="left" label="Previous week" onClick={() => go(shiftWeek(week, -1))} />
              <IconButton icon="right" label="Next week" onClick={() => go(shiftWeek(week, 1))} />
            </span>
            {week !== thisWeek && <Button kind="ghost" size="sm" onClick={() => go(thisWeek)}>Today</Button>}
          </div>
        </div>
        <div className="top-right">
          <ol className="steps" aria-label="Week status">
            <li className={list.length ? "done" : ""}><Icon name={list.length ? "check" : "minus"} size={15} />Planned</li>
            <li className="step-line" aria-hidden="true" />
            <li className={cartStep === "done" ? "done" : cartStep === "stale" ? "warn" : ""}>
              <Icon name={cartStep === "done" ? "check" : cartStep === "stale" ? "alert" : "minus"} size={15} />
              {cartStep === "stale" ? "Cart out of date" : "Cart sent"}
            </li>
          </ol>
          {!compact && action}
        </div>
      </div>

      {pending && <RatingBanner pending={pending} onRate={() => setRating(pendingEntry(pending))} onLater={rateLater} />}

      {entries.isPending || recipes.isPending ? <Loading /> : entries.error ? <ErrorNote error={entries.error} onRetry={() => entries.refetch()} /> : (
        <section className="grid" aria-label={`Dinners, ${weekRange(week)}`}>
          {nights.map((es, d) => {
            const date = nightDate(week, d);
            const tonight = isSameDay(date, today);
            const past = date < today && !tonight;
            return (
              <article key={d} className={["night", tonight && "tonight", dragOver === d && "over"].filter(Boolean).join(" ")}
                aria-label={`${DAYS_LONG[d]} ${shortDate(date)}`} {...dropProps(d)}>
                <div className="night-head">
                  <span className="dow">{DAYS[d]}</span><span className="date">{shortDate(date)}</span>
                  {tonight && <span className="pill tonight">Tonight</span>}
                  {!tonight && es[0]?.rating_count ? <span className="tag-quiet"><Icon name="check" size={14} />Rated</span> : null}
                </div>
                {es.length === 0 ? (
                  <button type="button" className="empty" onClick={() => setAdding(d)} disabled={!editable}>
                    <span className="plus"><Icon name="plus" size={18} /></span>
                    <b>No dinner yet</b>
                    <small>{!editable ? "Nothing planned" : drag ? "Add a recipe, or drag one from the tray" : "Add a recipe"}</small>
                  </button>
                ) : (
                  <div className={es.length > 1 ? "stack" : "single"}>
                    {es.map((e) => (
                      <NightCard key={e.id} entry={e} recipe={byId.get(e.recipe_id)} compact={es.length > 1} past={past || tonight}
                        editable={editable} canRate={canRate} me={me} draggable={drag} onRate={() => setRating(e)} onRemove={() => removeEntry(e)}
                        onMove={(day) => move.mutate({ id: e.id, day })} />
                    ))}
                  </div>
                )}
              </article>
            );
          })}
          <article className={["night", "tray-slot", dragOver === "tray" && "over"].filter(Boolean).join(" ")}
            aria-label="No night yet" {...dropProps(null)}>
            <div className="night-head">
              <span className="dow">No night yet</span><span className="date">{tray.length || ""}</span>
              {editable && <button type="button" className="link-btn" onClick={() => setAdding("tray")}><Icon name="plus" size={14} />Add</button>}
            </div>
            <div className="tray">
              {tray.map((e) => {
                const r = byId.get(e.recipe_id);
                return (
                  <div key={e.id} className="chip" draggable={drag}
                    onDragStart={(ev) => { ev.dataTransfer.setData(DRAG, String(e.id)); ev.dataTransfer.effectAllowed = "move"; }}>
                    <Photo src={r?.image} title={e.title ?? ""} className="th" />
                    <div className="chip-text">
                      <Link to={`/recipes/${e.recipe_id}`}><b>{e.title}</b></Link>
                      <small>{[formatMinutes(r?.total_minutes), r?.ratings.avg_family ? `★ ${formatAvg(r.ratings.avg_family)}` : "New"].filter(Boolean).join(" · ")}</small>
                    </div>
                    {editable && (
                      <Menu label={`Place ${e.title}`}>
                        {(close) => (
                          <>
                            <div className="menu-label">Move to</div>
                            <div className="menu-days">
                              {DAYS.map((dn, d) => (
                                <button key={d} type="button" role="menuitem"
                                  onClick={() => { close(); move.mutate({ id: e.id, day: d }); }}>{dn}</button>
                              ))}
                            </div>
                            <MenuItem icon="trash" danger onSelect={() => { close(); removeEntry(e); }}>Remove from week</MenuItem>
                          </>
                        )}
                      </Menu>
                    )}
                  </div>
                );
              })}
              {tray.length === 0 && <p className="tray-empty">Recipes added without a night wait here.</p>}
              {drag && tray.length > 0 && <div className="hint"><Icon name="arrow" size={14} />Drag onto a night to place it</div>}
            </div>
          </article>
        </section>
      )}

      <AddRecipeDialog open={adding !== null} week={week} day={typeof adding === "number" ? adding : null}
        onClose={() => setAdding(null)} />
      <RateDialog entry={rating} onClose={() => setRating(null)} />
      {compact && action && (
        <div className="dock">
          <span className="dock-text"><b>{placed}</b> {placed === 1 ? "dinner" : "dinners"} placed</span>
          {action}
        </div>
      )}
    </main>
  );
}

function NightCard({ entry, recipe, compact, past, editable, canRate, me, draggable, onRate, onRemove, onMove }: {
  entry: PlanEntry; recipe?: Recipe; compact: boolean; past: boolean; editable: boolean; canRate: boolean; me: number | null;
  draggable: boolean; onRate: () => void; onRemove: () => void; onMove: (day: number | null) => void;
}) {
  const mine = entry.my_rating;
  const others = (entry.ratings ?? []).filter((r) => (r.member_id ?? null) !== me);
  const title = entry.title ?? recipe?.title ?? "Recipe";
  const avg = formatAvg(recipe?.ratings.avg_family);
  return (
    <div className="card" draggable={draggable}
      onDragStart={(ev) => { ev.dataTransfer.setData(DRAG, String(entry.id)); ev.dataTransfer.effectAllowed = "move"; }}>
      {!compact && <Link to={`/recipes/${entry.recipe_id}`} tabIndex={-1} aria-hidden="true" className="photo-link"><Photo src={recipe?.image} title={title} /></Link>}
      <div className="card-body">
        <div className="card-title-row">
          <h3><Link to={`/recipes/${entry.recipe_id}`}>{title}</Link></h3>
          {editable && (
            <Menu label={`Options for ${title}`}>
              {(close) => (
                <>
                  {canRate && (past || entry.rating_count > 0) && <MenuItem icon="star" onSelect={() => { close(); onRate(); }}>{mine ? "Change your rating" : "Rate dinner"}</MenuItem>}
                  <div className="menu-label">Move to</div>
                  <div className="menu-days">
                    {DAYS.map((dn, d) => (
                      <button key={d} type="button" role="menuitem" aria-current={entry.day === d} disabled={entry.day === d}
                        onClick={() => { close(); onMove(d); }}>{dn}</button>
                    ))}
                  </div>
                  <MenuItem icon="inbox" onSelect={() => { close(); onMove(null); }}>No night yet</MenuItem>
                  <MenuItem icon="trash" danger onSelect={() => { close(); onRemove(); }}>Remove from week</MenuItem>
                </>
              )}
            </Menu>
          )}
        </div>
        <div className="meta">
          {formatMinutes(recipe?.total_minutes) && <span><Icon name="clock" size={14} />{formatMinutes(recipe?.total_minutes)}</span>}
          {avg ? <span title={`Family average of ${recipe?.ratings.rating_count} ratings`}><Star /><b>{avg}</b><span className="muted">({recipe?.ratings.rating_count})</span></span>
            : <span className="muted">New recipe</span>}
          {entry.multiplier !== 1 && <span className="muted">×{entry.multiplier}</span>}
        </div>
        {others.length > 0 && <PeopleRatings rows={others} me={me} label={`Ratings of ${title}`} />}
        {mine ? (
          <button type="button" className="rated" onClick={onRate} disabled={!canRate} aria-label={`You rated it ${mine.family} of 5. Change your rating`}>
            <span className="who">You</span>
            <MiniStars n={mine.family} size={13} />
            {mine.note && <span className="rated-note">“{mine.note}”</span>}
          </button>
        ) : past && canRate ? (
          <button type="button" className="rate-cta" onClick={onRate}><Icon name="star" size={14} />How was it?</button>
        ) : null}
        {entry.rating_count > 1 && entry.rating_avg != null && <small className="muted">Dinner average {formatAvg(entry.rating_avg)} from {entry.rating_count}</small>}
      </div>
    </div>
  );
}
