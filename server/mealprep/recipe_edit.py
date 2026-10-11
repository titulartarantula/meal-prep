"""Editing a recipe's ingredient lines in the app (2026-10-11, PATCH /recipes/{id} `ingredients`).

The app sends the lines as the recipe screen shows them, one per line: an attached sub-recipe's name is a heading line
("Batter for 24 crêpes:") over its lines, and a new heading ("For the sauce:") starts a sub-recipe of its own. A line
whose text is unchanged keeps everything known about it (name, amount, unit, its sub-recipe and flags, so remembered
products still match); a changed or new line is structured again, by the AI like an imported recipe's lines (one call
for all of them), else by the deterministic clean-up, so an edit never fails over the AI. A line whose sub-recipe page
was attached ("Batter for 24 crêpes, page 191") isn't shown, so it isn't sent: it stays in place while its sub-recipe
has lines, and comes back as an ordinary line (bought, its page offered again) once they are all deleted.
"""
import difflib
import logging
from typing import NamedTuple

from .exchange.jsonld import _heading
from .importers.structure import _ref_page, structure_ingredients
from .ingredients import clean_ingredient, likely_on_hand
from .models import Ingredient

log = logging.getLogger(__name__)


def _clean(line: str) -> str:
    return " ".join(line.split())


class Line(NamedTuple):
    text: str
    section: str | None   # the heading above it, if any
    block: int            # which heading (0 = none yet)
    attached: bool        # that heading is a sub-recipe the recipe already had


def parse_lines(lines: list[str], known: set[str]) -> list[Line]:
    """The ingredient lines, blank ones dropped, each with the heading above it. A heading is a known sub-recipe's name
    followed by ":" (any case; kept as it was named) or a new heading ("For the sauce:" → "Sauce")."""
    by_lower = {k.lower(): k for k in known}
    out, section, block, attached = [], None, 0, False
    for raw in lines:
        line = _clean(raw)
        if not line:
            continue
        name = by_lower.get(line[:-1].strip().lower()) if line.endswith(":") else None
        head = name or _heading(line)
        if head:
            section, block, attached = head, block + 1, name is not None
            continue
        out.append(Line(line, section, block, attached))
    return out


def structure(ai, lines: list[str]) -> list[Ingredient]:
    """New or changed lines, structured by the AI (like an import's), else deterministically."""
    if not lines:
        return []
    if ai is not None:
        try:
            return structure_ingredients(ai, lines, strict=True)
        except Exception as e:   # AIError, a timeout, an odd answer
            log.warning("recipe edit: AI couldn't structure %d lines (%s); deterministic clean-up instead", len(lines), e)
    out = []
    for line in lines:
        i = clean_ingredient(Ingredient(raw=line, name=line.lower()))
        out.append(i.model_copy(update={"likely_on_hand": likely_on_hand(i.name), "ref_page": _ref_page(line)}))
    return out


def edit_ingredients(ai, old: list[Ingredient], lines: list[str]) -> list[Ingredient]:
    """The recipe's ingredients after the edit (see the module docstring)."""
    hidden = [(k, i) for k, i in enumerate(old) if i.expanded]
    shown = [i for i in old if not i.expanded]
    items = parse_lines(lines, {i.sub_recipe for i in old if i.sub_recipe})
    out: list[Ingredient | None] = [None] * len(items)
    new: list[int] = []
    m = difflib.SequenceMatcher(None, [i.raw for i in shown], [it.text for it in items], autojunk=False)
    used: set[int] = set()
    for tag, a, _, b, b2 in m.get_opcodes():
        if tag == "equal":
            for k in range(b, b2):
                out[k] = shown[a + k - b]
                used.add(a + k - b)
    for k, it in enumerate(items):   # a line moved elsewhere is still the same line
        if out[k] is None:
            j = next((j for j, o in enumerate(shown) if j not in used and o.raw == it.text), None)
            if j is None:
                new.append(k)
            else:
                out[k] = shown[j]
                used.add(j)
    for k, ing in zip(new, structure(ai, [items[k].text for k in new])):
        out[k] = ing.model_copy(update={"expanded": False})
    # Sections: the heading above each line. The screen shows no end to an attached sub-recipe, so the recipe's own
    # lines after it (unchanged, not in it before) end it, with what follows up to the next heading.
    ended = None
    for k, it in enumerate(items):
        o = out[k]
        if it.attached and ended != it.block and k not in new and o.sub_recipe is None:
            ended = it.block
        section = None if ended == it.block else it.section
        if o.sub_recipe != section:
            out[k] = o.model_copy(update={"sub_recipe": section})
    result: list[Ingredient] = list(out)
    for old_index, h in hidden:
        if h.sub_recipe is None:   # no sub-recipe named to follow: it stays where it was
            result.insert(min(old_index, len(result)), h)
            continue
        first = next((k for k, i in enumerate(result) if i.sub_recipe == h.sub_recipe and not i.expanded), None)
        if first is not None:
            result.insert(first, h)
        else:   # its sub-recipe is gone: the line itself is bought again
            back = h.model_copy(update={"expanded": False, "sub_recipe": None, "ref_page": _ref_page(h.raw)})
            result.insert(min(old_index, len(result)), back)
    return result
