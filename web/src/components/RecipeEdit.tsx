// Edit recipe and Edit source, as on Android: PATCH /recipes/{id} with only what changed and the revision the edit
// began from (409 = changed elsewhere meanwhile; the edits stay in the dialog). Edit source: Book (the household's books,
// then the server's book search; page) or Other (a name, "Used before" names, a note).
import { useEffect, useId, useState } from "react";
import { ApiError } from "../api/client";
import { searchBooks, useEditRecipe, useRecipeSources } from "../api/queries";
import type { BookHit, Recipe } from "../api/types";
import { changes, editMessage, fromRecipe, problems, type RecipeDraft } from "../lib/recipeDraft";
import {
  bookQuery, foundBooks, otherNames, resolveBook, resolveName, savedBook, suggestBooks, suggestNames, typedBook,
  yourBooks, type BookChoice, type BookSuggestion,
} from "../lib/sources";
import { Button, ErrorNote, Modal } from "./ui";

function errorText(e: unknown): string | null {
  if (!e) return null;
  return e instanceof ApiError ? editMessage(e.status, e.message) : String(e);
}

/** Mounted while open (so it starts from the recipe as it is now). */
export function EditRecipeDialog({ recipe, onClose }: { recipe: Recipe; onClose: () => void }) {
  const edit = useEditRecipe(recipe.id!);
  const [draft, setDraft] = useState<RecipeDraft>(() => fromRecipe(recipe));
  const [tried, setTried] = useState(false);
  const [asking, setAsking] = useState(false);
  const changed = changes(draft, recipe) != null;
  const shown = tried ? problems(draft) : {};
  const leave = () => (changed ? setAsking(true) : onClose());
  const save = () => {
    setTried(true);
    if (Object.keys(problems(draft)).length) return;
    const body = changes(draft, recipe);
    if (!body) return onClose();
    edit.mutate(body, { onSuccess: onClose });
  };
  const set = (k: keyof RecipeDraft) => (v: string) => setDraft((d) => ({ ...d, [k]: v }));
  return (
    <Modal open onClose={leave} title="Edit recipe" wide
      footer={asking ? (
        <>
          <span className="foot-ask" role="alert">Discard your changes?</span>
          <span className="spacer" />
          <Button kind="outline" onClick={() => setAsking(false)}>Keep editing</Button>
          <Button kind="danger" onClick={onClose}>Discard</Button>
        </>
      ) : (
        <>
          <span className="spacer" />
          <Button kind="outline" onClick={leave}>Cancel</Button>
          <Button kind="primary" onClick={save} disabled={edit.isPending}>{edit.isPending ? "Saving…" : "Save changes"}</Button>
        </>
      )}>
      <div className="edit-form">
        {edit.error != null && <div className="note error" role="alert"><span>{errorText(edit.error)}</span></div>}
        <Field label="Title" value={draft.title} onChange={set("title")} problem={shown.title} />
        <Field label="Servings" optional value={draft.servings} onChange={set("servings")} problem={shown.servings} number />
        <Field label="Ingredients" value={draft.ingredients} onChange={set("ingredients")} rows={8}
          help="One per line. A line ending in “:” starts a group, like “For the sauce:”." />
        <Field label="Steps" value={draft.steps} onChange={set("steps")} rows={8} help="One step per line." />
        <details className="edit-more">
          <summary>Notes, description, times and yield</summary>
          <Field label="Notes from the recipe" optional value={draft.notes} onChange={set("notes")} rows={3} />
          <Field label="Description" optional value={draft.description} onChange={set("description")} rows={2} />
          <div className="edit-times">
            <Field label="Prep minutes" optional value={draft.prep} onChange={set("prep")} problem={shown.prep} number />
            <Field label="Cook minutes" optional value={draft.cook} onChange={set("cook")} problem={shown.cook} number />
            <Field label="Total minutes" optional value={draft.total} onChange={set("total")} problem={shown.total} number />
          </div>
          <Field label="Yield" optional value={draft.yieldText} onChange={set("yieldText")} help="Like “Makes 24 cookies”." />
        </details>
        <p className="muted small">Changed ingredient lines are read again; a cart already built from the old lines is marked out of date.</p>
      </div>
    </Modal>
  );
}

function Field({ label, value, onChange, problem, help, rows, number, optional }: {
  label: string; value: string; onChange: (v: string) => void; problem?: string; help?: string; rows?: number; number?: boolean;
  optional?: boolean;
}) {
  const id = useId();
  const note = problem ?? help;
  const props = {
    id, value, "aria-invalid": problem ? true : undefined, "aria-describedby": note ? `${id}-n` : undefined,
    onChange: (e: { target: { value: string } }) => onChange(e.target.value),
  };
  return (
    <div className={`field${problem ? " invalid" : ""}`}>
      <label htmlFor={id}>{label}{optional && <small> (optional)</small>}</label>
      {rows ? <textarea rows={rows} {...props} /> : <input type="text" inputMode={number ? "numeric" : undefined} {...props} />}
      {note && <small id={`${id}-n`} className={problem ? "field-problem" : undefined} role={problem ? "alert" : undefined}>{note}</small>}
    </div>
  );
}

/** Edit source (Add book for an unknown book); mounted while open. Website recipes (NYT Cooking too) have no source to edit. */
export function EditSourceDialog({ recipe, onClose }: { recipe: Recipe; onClose: () => void }) {
  const edit = useEditRecipe(recipe.id!);
  const sources = useRecipeSources().data ?? [];
  const wasOther = recipe.source_kind === "other";
  const [kind, setKind] = useState<"book" | "other">(wasOther ? "other" : "book");
  const [book, setBook] = useState<BookChoice>(() => (wasOther ? savedBook({}) : savedBook(recipe)));
  const [pageText, setPage] = useState(wasOther ? "" : recipe.source_ref ?? "");
  const [name, setName] = useState(wasOther ? recipe.source_title ?? "" : "");
  const [note, setNote] = useState(wasOther ? recipe.source_ref ?? "" : "");
  const [nameMissing, setNameMissing] = useState(false);
  const [hits, setHits] = useState<BookHit[]>([]);
  // the book search, debounced; a newer query cancels the one before
  const q = kind === "book" && !book.picked ? bookQuery(book.title) : null;
  useEffect(() => {
    if (!q) { setHits([]); return; }
    const ac = new AbortController();
    const t = setTimeout(() => searchBooks(q, ac.signal).then(setHits, () => {}), 300);
    return () => { clearTimeout(t); ac.abort(); };
  }, [q]);
  const yours = yourBooks(sources);
  const names = otherNames(sources);
  const rows = suggestBooks(yours, foundBooks(hits), book);
  const pick = (s: BookSuggestion) => setBook({ title: s.title, author: s.author, isbn: s.isbn, picked: true });
  const save = () => {
    if (kind === "book") {
      const b = resolveBook(book, yours);
      edit.mutate({ source_kind: "book", source_title: b.title, source_ref: pageText.trim() || null, source_author: b.author,
        source_isbn: b.isbn }, { onSuccess: onClose });
    } else if (!name.trim()) {
      setNameMissing(true);
    } else {
      edit.mutate({ source_kind: "other", source_title: resolveName(name, names), source_ref: note.trim() || null,
        source_author: null, source_isbn: null }, { onSuccess: onClose });
    }
  };
  const missing = nameMissing && !name.trim();
  return (
    <Modal open onClose={onClose} title="Where is it from?"
      footer={<>
        <span className="spacer" />
        <Button kind="outline" onClick={onClose}>Cancel</Button>
        <Button kind="primary" onClick={save} disabled={edit.isPending}>Save</Button>
      </>}>
      <div className="edit-form">
        <div className="seg" role="radiogroup" aria-label="Kind of source">
          {(["book", "other"] as const).map((k) => (
            <button key={k} type="button" role="radio" aria-checked={kind === k} className={kind === k ? "on" : ""}
              onClick={() => setKind(k)}>{k === "book" ? "Book" : "Other"}</button>
          ))}
        </div>
        {kind === "book" ? (
          <>
            <div className="field">
              <label htmlFor="src-book">Book</label>
              <input id="src-book" type="text" autoComplete="off" value={book.title} aria-describedby="src-book-n"
                onChange={(e) => setBook(typedBook(book, e.target.value))} />
              <small id="src-book-n">{book.picked && book.author ? `By ${book.author}. ` : ""}Leave it empty if you don't know; you can add it later.</small>
            </div>
            <BookRows label="Your books" rows={rows.yours} onPick={pick} />
            <BookRows label="Book search" rows={rows.found} onPick={pick} />
            <div className="field">
              <label htmlFor="src-page">Page <small>(optional)</small></label>
              <input id="src-page" type="text" value={pageText} onChange={(e) => setPage(e.target.value)} />
            </div>
          </>
        ) : (
          <>
            <div className={`field${missing ? " invalid" : ""}`}>
              <label htmlFor="src-name">Name</label>
              <input id="src-name" type="text" value={name} aria-invalid={missing || undefined} aria-describedby="src-name-n"
                onChange={(e) => setName(e.target.value)} />
              <small id="src-name-n" className={missing ? "field-problem" : undefined} role={missing ? "alert" : undefined}>
                {missing ? "Add a name to save" : "Needed, e.g. Grandma's cards"}
              </small>
            </div>
            {suggestNames(names, name).length > 0 && (
              <div className="used-before" role="group" aria-labelledby="src-used">
                <span id="src-used" className="muted small">Used before</span>
                {suggestNames(names, name).map((n) => <Button key={n} kind="outline" size="sm" onClick={() => setName(n)}>{n}</Button>)}
              </div>
            )}
            <div className="field">
              <label htmlFor="src-note">Note <small>(optional)</small></label>
              <input id="src-note" type="text" value={note} aria-describedby="src-note-n" onChange={(e) => setNote(e.target.value)} />
              <small id="src-note-n">Shown after the name, e.g. the blue binder</small>
            </div>
          </>
        )}
        <ErrorNote error={edit.error} />
      </div>
    </Modal>
  );
}

function BookRows({ label, rows, onPick }: { label: string; rows: BookSuggestion[]; onPick: (b: BookSuggestion) => void }) {
  const id = useId();
  if (!rows.length) return null;
  return (
    <section className="book-rows" aria-labelledby={id}>
      <h3 id={id} className="muted small">{label}</h3>
      <ul>
        {rows.map((b, i) => (
          <li key={`${b.title}-${i}`}>
            <button type="button" onClick={() => onPick(b)}>
              <b>{[b.title, b.subtitle].filter(Boolean).join(": ")}</b>
              {(b.author || b.year) && <small>{[b.author, b.year].filter(Boolean).join(" · ")}</small>}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
