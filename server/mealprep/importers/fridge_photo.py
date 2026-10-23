"""Fridge photos (0.14.0): one vision call reads 1–6 photos of the fridge against the current fridge list.

The answer is only a proposal (see `fridge.proposals`): what of the list is seen (with an estimate of how much), and
perishables that aren't on it. Nothing is saved from here, and a photo never removes anything by itself."""
import json, re

from ..ai.base import AIError
from ..staples import normalise_unit

FAILED = "Couldn't read the fridge in those photos."
MAX_QTY = 10_000
VOCAB_SHOWN = 300

PROMPT = """These photos show one household fridge: shelves, drawers and the door, possibly in several photos that overlap.
The household keeps a list of the perishables in it. The list now (id, name, amount): {items}
Look for PERISHABLES only: produce (fruit, vegetables), dairy, eggs, fresh herbs, meat and fish, tofu, leftovers and opened sauces or jars that spoil. Skip condiments, drinks and long-keeping jars unless they are already on the list.
Return ONLY JSON:
{{"seen": [{{"id": <a list id>, "estimate": {{"qty": <number>, "unit": <unit or null>}} or null, "confidence": "high"|"medium"|"low", "where": <a few words: "door", "top shelf", "crisper drawer">}}],
 "new": [{{"name": <what you would call it on a shopping list>, "estimate": ..., "confidence": ..., "where": ...}}]}}
- "seen": every list item you can see in a photo (once, even if several photos show it).
- "new": perishables you can see that are not on the list. Name them the way the household does where one of these names fits: {vocab}
- "estimate": how much you can see. unit null = a count (6 eggs, 3 lemons); otherwise a unit such as bag, bunch, container, carton, package, head, g, kg, ml, l, cup. Use null when you can't tell (a closed carton, an opaque tub).
- "confidence": how sure you are that it is this item and this amount. Use "low" when it is partly hidden or you are guessing.
- Only what the photos show. Never list an item because it usually is in a fridge.
If the photos don't show the inside of a fridge, return {{"seen": [], "new": [], "not_a_fridge": true}}."""


class ReadFailed(Exception):
    def __init__(self, text: str = FAILED):
        super().__init__(text)


def _qty(v) -> float | None:
    if isinstance(v, bool):
        return None
    if isinstance(v, str):
        m = re.search(r"\d+(?:\.\d+)?", v)
        v = m.group(0) if m else None
    try:
        q = float(v) if v is not None else None
    except (TypeError, ValueError):
        return None
    return None if q is None or q != q or q < 0 else min(q, MAX_QTY)


def _estimate(v) -> dict | None:
    if not isinstance(v, dict):
        return None
    q = _qty(v.get("qty"))
    if q is None:
        return None
    try:
        unit = normalise_unit(v.get("unit")) if isinstance(v.get("unit"), (str, type(None))) else None
    except ValueError:
        unit = None
    return {"qty": q, "unit": unit}


def _confidence(v) -> str:
    v = str(v or "").strip().lower()
    return v if v in ("high", "medium", "low") else "medium"


def _where(v) -> str | None:
    v = " ".join(str(v).split())[:40] if isinstance(v, str) else ""
    return v or None


def _name(v) -> str | None:
    if not isinstance(v, str):
        return None
    v = " ".join(v.split())[:80]
    return (v[0].upper() + v[1:]) if v else None


def coerce(d, items) -> dict:
    """The answer as {seen: [...], new: [...]}: unknown or repeated ids dropped, units checked, amounts clamped,
    new names cleaned and one per food. Raises ReadFailed for an answer that isn't the expected shape."""
    if not isinstance(d, dict) or not isinstance(d.get("seen", []), list) or not isinstance(d.get("new", []), list):
        raise AIError("not the expected object")
    if d.get("not_a_fridge"):
        raise ReadFailed()
    from ..fridge import rule_key
    ids, seen = {it.id for it in items}, []
    for s in d.get("seen") or []:
        if not isinstance(s, dict):
            continue
        try:
            iid = int(s.get("id"))
        except (TypeError, ValueError):
            continue
        if iid in ids:
            ids.discard(iid)
            seen.append({"id": iid, "estimate": _estimate(s.get("estimate")), "confidence": _confidence(s.get("confidence")),
                         "where": _where(s.get("where"))})
    new, keys = [], set()
    for n in d.get("new") or []:
        if not isinstance(n, dict) or not (name := _name(n.get("name"))) or rule_key(name) in keys:
            continue
        keys.add(rule_key(name))
        new.append({"name": name, "estimate": _estimate(n.get("estimate")), "confidence": _confidence(n.get("confidence")),
                    "where": _where(n.get("where"))})
    return {"seen": seen, "new": new}


def read(provider, paths: list[str], items, vocab: list[str]) -> dict:
    """One vision call (one retry when the answer isn't readable JSON or the call fails) → coerce()."""
    listed = [{"id": it.id, "name": it.name, "qty": it.qty, "unit": it.unit} for it in items]
    prompt = PROMPT.format(items=json.dumps(listed, ensure_ascii=False) if listed else "(empty)",
                           vocab=json.dumps(vocab[:VOCAB_SHOWN], ensure_ascii=False))
    for attempt in range(2):
        try:
            return coerce(provider.complete_json(prompt, images=paths), items)
        except ReadFailed:
            raise
        except Exception:   # AIError, timeouts, an answer of the wrong shape
            if attempt:
                raise ReadFailed()
