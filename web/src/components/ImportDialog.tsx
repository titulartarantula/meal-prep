import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { getImport, importFile, keys, useShareImport } from "../api/queries";
import type { ImportReport } from "../api/types";
import { Icon } from "./Icon";
import { Button, ErrorNote, Modal, Spinner } from "./ui";

type Stage = { kind: "idle" } | { kind: "reading"; id: number } | { kind: "preview"; report: ImportReport; file: File }
  | { kind: "applying"; id: number } | { kind: "done"; report: ImportReport };

/** Add recipes: an NYT Cooking link, or a file (JSON-LD, saved page, PDF, Word, text) previewed before import. */
export function ImportDialog({ open, onClose, onOpenRecipe }: { open: boolean; onClose: () => void; onOpenRecipe: (id: number) => void }) {
  const [tab, setTab] = useState<"link" | "file">("link");
  const [link, setLink] = useState("");
  const share = useShareImport();
  const [stage, setStage] = useState<Stage>({ kind: "idle" });
  const [choices, setChoices] = useState<Record<string, string>>({});
  const [error, setError] = useState<unknown>(null);
  const qc = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);

  const reset = () => { setLink(""); share.reset(); setStage({ kind: "idle" }); setError(null); setFile(null); setChoices({}); };
  const close = () => { reset(); onClose(); };

  // Poll a document read or an import job until it finishes.
  useEffect(() => {
    if (stage.kind !== "reading" && stage.kind !== "applying") return;
    let stop = false;
    const tick = async () => {
      try {
        const r = await getImport(stage.id);
        if (stop) return;
        if (r.status === "failed") { setError(new Error(r.error || "The import failed.")); setStage({ kind: "idle" }); return; }
        if (r.status === "done") {
          if (stage.kind === "reading") toPreview(r, file!);
          else { setStage({ kind: "done", report: r }); qc.invalidateQueries({ queryKey: keys.recipes }); }
          return;
        }
        setTimeout(tick, 1200);
      } catch (e) { if (!stop) { setError(e); setStage({ kind: "idle" }); } }
    };
    const t = setTimeout(tick, 800);
    return () => { stop = true; clearTimeout(t); };
  }, [stage]); // eslint-disable-line react-hooks/exhaustive-deps

  function toPreview(report: ImportReport, f: File) {
    setChoices(Object.fromEntries(report.items.map((i) => [i.key, i.action])));
    setStage({ kind: "preview", report, file: f });
  }

  async function pick(f: File) {
    setFile(f); setError(null);
    try {
      const r = await importFile(f, true);
      if (r.status === "running" && r.id) setStage({ kind: "reading", id: r.id });
      else toPreview(r, f);
    } catch (e) { setError(e); }
  }

  async function apply() {
    if (stage.kind !== "preview") return;
    setError(null);
    try {
      const r = await importFile(stage.file, false, choices);
      setStage({ kind: "applying", id: r.id! });
    } catch (e) { setError(e); }
  }

  const adding = Object.values(choices).filter((a) => a !== "skip").length;

  return (
    <Modal open={open} onClose={close} title="Add a recipe" wide
      footer={tab === "file" && stage.kind === "preview" ? <>
        <span className="muted">{adding} of {stage.report.items.length} will be imported</span>
        <span className="spacer" />
        <Button kind="outline" onClick={reset}>Choose another file</Button>
        <Button kind="primary" onClick={apply} disabled={!adding}>Import {adding || ""}</Button>
      </> : undefined}>
      <div className="seg tabs" role="tablist">
        <button type="button" role="tab" aria-selected={tab === "link"} className={tab === "link" ? "on" : ""} onClick={() => setTab("link")}>
          <Icon name="link" size={15} />NYT Cooking link</button>
        <button type="button" role="tab" aria-selected={tab === "file"} className={tab === "file" ? "on" : ""} onClick={() => setTab("file")}>
          <Icon name="upload" size={15} />From a file</button>
      </div>

      {tab === "link" ? (
        <form className="import-link" onSubmit={(e) => { e.preventDefault(); share.mutate(link); }}>
          <label className="field">
            <span>Paste the recipe's link</span>
            <input type="url" inputMode="url" required placeholder="https://cooking.nytimes.com/recipes/…" value={link}
              onChange={(e) => { setLink(e.target.value); share.reset(); }} />
          </label>
          {share.isPending && <p className="working"><Spinner />Reading the recipe… this takes about half a minute.</p>}
          {share.data ? (
            <div className="note ok">
              <Icon name="check" size={17} />
              <span><b>{share.data.recipe.title}</b> {share.data.existing ? "is already in your library." : "is in your library."}</span>
              <Button size="sm" kind="outline" onClick={() => onOpenRecipe(share.data!.recipe.id)}>Open</Button>
            </div>
          ) : (
            <div className="form-actions"><Button kind="primary" type="submit" disabled={!link || share.isPending}>Import recipe</Button></div>
          )}
          <ErrorNote error={share.error} />
        </form>
      ) : stage.kind === "idle" ? (
        <div className="dropzone" onDragOver={(e) => e.preventDefault()}
          onDrop={(e) => { e.preventDefault(); const f = e.dataTransfer.files[0]; if (f) pick(f); }}>
          <Icon name="upload" size={26} />
          <b>Drop a file here, or <button type="button" className="link-btn" onClick={() => fileRef.current?.click()}>choose one</button></b>
          <small>Recipe exports (JSON-LD), saved web pages, PDF, Word or text files. Up to 25 MB.</small>
          <input ref={fileRef} type="file" hidden accept=".json,.jsonld,.html,.htm,.pdf,.docx,.txt,.md"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) pick(f); }} />
          <ErrorNote error={error} />
        </div>
      ) : stage.kind === "reading" || stage.kind === "applying" ? (
        <p className="working"><Spinner />{stage.kind === "reading" ? `Finding the recipes in ${file?.name}…` : "Importing…"}</p>
      ) : stage.kind === "preview" ? (
        <>
          {stage.report.warnings.map((w) => <p key={w} className="note">{w}</p>)}
          <ul className="import-items">
            {stage.report.items.map((i) => (
              <li key={i.key}>
                <label>
                  <input type="checkbox" disabled={i.status === "failed"} checked={choices[i.key] !== "skip"}
                    onChange={(e) => setChoices((c) => ({ ...c, [i.key]: e.target.checked ? (i.status === "duplicate" ? "add" : "add") : "skip" }))} />
                  <span><b>{i.title ?? "Untitled recipe"}</b>
                    <small className={i.status === "failed" ? "err" : "muted"}>
                      {i.status === "new" ? "New" : i.status === "duplicate" ? `Already in your library${i.match ? ` (${i.match.title})` : ""}` : i.reasons.join("; ") || i.status}
                    </small></span>
                </label>
              </li>
            ))}
          </ul>
          <ErrorNote error={error} />
        </>
      ) : (
        <div className="note ok">
          <Icon name="check" size={17} />
          <span>Done: {Object.entries(stage.report.counts).filter(([, n]) => n).map(([k, n]) => `${n} ${k}`).join(", ")}.</span>
          <Button size="sm" kind="outline" onClick={close}>Close</Button>
        </div>
      )}
    </Modal>
  );
}
