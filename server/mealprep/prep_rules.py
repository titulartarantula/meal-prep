"""Deterministic food-safety pass over the LLM's prep plan (keyword rules; tested directly).

Sunday is prep only: a hot task is taken off the plan and put on its cook card(s). Shelf-life rules only
ever make a task stricter (ok → freeze_then_thaw → day_of), never looser than the LLM said, and thaw
instructions are per night (a task batched across Tue + Fri only freezes the Fri portion). Kit contents
are checked too. Every placed night ends up with exactly one kit."""
import re
from dataclasses import dataclass, field

from .models import MealIn, PrepTask, RawPrepPlan

SECTIONS = ("knife", "sauces", "proteins", "pack")
TITLES = {"knife": "Knife work", "sauces": "Sauces, dressings & spice blends", "proteins": "Proteins",
          "pack": "Pack & label"}
DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
LATE = 2   # a night more than 2 days after the prep Sunday is "late"
CHICKEN_LATE = 3   # raw chicken keeps through Wednesday; Thursday onward is frozen (decided 2026-10-04)
_RANK = {"ok": 0, "freeze_then_thaw": 1, "day_of": 2}


def _re(p):
    return re.compile(p, re.I)


# Hot verbs only count as an instruction: at the start of the task or of a clause ("…, then sauté",
# "Squash – roast", "Onions & sauté"), in base form — so "for roasting", "toasted sesame oil", "the stir-fry
# sauce", "brown sugar" don't trip it.
_SEP = r"(?:^|[.;:!,&/]\s*|\s[–—-]\s*|\b(?:then|and)\s+)"
_PREFIX = r"(?:(?:hard|soft|pre|par|pan|air|deep|stir|slow|pressure)-?)?"
_HOT_VERB = (r"(?:heat|boil|blanch|simmer|saut[eé]|fry|sear|grill|broil|braise|poach|steam|roast|bake|toast|cook|"
             r"melt|carameli[sz]e|reduce|microwave|char|render|scald|brown(?!\s+(?:sugar|rice|lentils?|bread|onions?)\b)|"
             r"warm(?=\s+(?:the|up|it|them)\b))")
HOT = _re(rf"{_SEP}{_PREFIX}{_HOT_VERB}\b|\bbring\b.{{0,40}}?\bto\s+(?:a\s+)?(?:boil|simmer)\b")
# A kit item that was cooked (kit contents are noun phrases, not instructions).
COOKED = _re(r"\b(?:cooked|boiled|steamed|simmered|saut[eé]ed|fried|seared|braised|poached|melted|blanched|"
             r"carameli[sz]ed)\b")
CUT = _re(r"\b(?:cut|dice|slice|chop|cube|mince|julienne|shred|grate|peel|mash|quarter|halve|wedge|spiralize|"
          r"tear|pick|pur[eé]e)\w*")
BROWNING = _re(r"\b(?:avocados?|apples?(?!\s+(?:cider|juice|sauce|butter))|pears?|bananas?|"
               r"potato(?:es)?(?!\s+starch))\b")
HERBS = _re(r"(?<!dried )(?<!dry )\b(?:basil|cilantro|coriander leaves|mint|dill(?!\s+pickles?)|parsley)\b")
FISH = _re(r"(?<!cooked )(?<!canned )(?<!smoked )\b(?:\w*fish(?!\s+sauce)|salmon|cod|tuna|halibut|tilapia|trout|"
           r"haddock|snapper|mackerel|sea bass|branzino|sole|pollock|mahi(?:-mahi)?|sardines?|shrimps?|prawns?|"
           r"scallops?|mussels?|clams?|oysters?|octopus|crabs?|lobsters?|squid|calamari|seafood)\b")
GROUND = _re(r"\b(?:ground|minced)\s+(?:beef|pork|turkey|chicken|lamb|veal|bison|meat)\b|"
             r"\b(?:beef|pork|lamb|turkey|chicken)\s+mince\b|"
             r"\b(?:meatballs?|patt(?:y|ies)|burgers?(?!\s+buns?)|chorizo|kofta)\b|"
             r"(?<!smoked )(?<!cooked )\bsausages?\b")
CHICKEN = _re(r"(?<!cooked )\b(?:chicken(?!\s+(?:stock|broth|bouillon))|wings?|thighs?|drumsticks?|turkey)\b")
MARINADE = _re(r"\bmarina(?:te|de|ting|ted)")
TOSS = _re(r"\b(?:toss|coat|soak|submerge)\w*\b")
ACID = _re(r"\b(?:lemons?|limes?|vinegar|citrus|orange|grapefruit|wine|buttermilk|yog(?:h)?urt|pineapple|tamarind)\b")


def is_hot(text: str) -> bool:
    return bool(HOT.search(text.strip()))


def _night_before(offset: int | None) -> str:
    return f"{DAYS[(offset - 1) % 7]} night" if offset is not None else "the night before you cook it"


def _thaw(offset: int | None, lead: str) -> str:
    return f"{lead}; move it to the fridge {_night_before(offset)}."


def _subject(text: str) -> str:
    """The part of a knife task naming what is cut: drop the allocation after ':' and 'for …' phrases, so
    "Dice 3 onions: 2 Tue tacos, 1 Fri salmon" isn't about salmon."""
    return re.sub(r"\bfor\b[^,;.]*", "", text.split(":", 1)[0], flags=re.I)


@dataclass
class _Hit:
    shelf: str
    reason: str
    obj: str | None = None      # what gets frozen: "the salmon" / "it"
    suffix: str = ""           # " in the marinade"
    late_after: int = LATE     # nights with offset > this are "late" for this hit


def _rule(text: str, section: str, offsets: list[int | None], acidic: bool = False) -> _Hit | None:
    offsets = offsets or [None]
    late = any(o is None or o > LATE for o in offsets)
    hits = []
    if section != "pack" and CUT.search(text):
        if m := BROWNING.search(text):
            hits.append(_Hit("day_of", f"cut {m.group(0).lower()} browns — do it on the night"))
        if m := HERBS.search(text):
            hits.append(_Hit("day_of", f"{m.group(0).lower()} wilts — chop it on the night"))
    if section == "proteins":
        marinade = MARINADE.search(text) or TOSS.search(text)
        if marinade and (ACID.search(text) or (acidic and MARINADE.search(text))):
            if FISH.search(text) and any(o is None or o > 0 for o in offsets):
                hits.append(_Hit("day_of", "an acidic marinade 'cooks' raw fish — marinate it on the night"))
            elif CHICKEN.search(text) and late:
                hits.append(_Hit("freeze_then_thaw", "chicken in an acidic marinade for more than 2 days — "
                                 "freeze it in the marinade", "it", " in the marinade"))
    scope = text if section == "proteins" else _subject(text) if section == "knife" else ""
    strict = FISH.search(scope) or GROUND.search(scope)
    raw, after = (strict, LATE) if strict else (CHICKEN.search(scope), CHICKEN_LATE)
    if raw and any(o is None or o > after for o in offsets):
        what = raw.group(0).lower()
        hits.append(_Hit("freeze_then_thaw", f"raw {what} for a night more than {after} days out — freeze it",
                         f"the {what}", late_after=after))
    return max(hits, key=lambda h: _RANK[h.shelf]) if hits else None


def shelf_rule(text: str, section: str, offsets: list[int | None],
               acidic: bool = False) -> tuple[str, str | None, str] | None:
    """(shelf_life, thaw, reason) the rules demand for this task, or None. offsets: days after prep Sunday of
    every night the task serves (None = no night yet, treated as late). acidic: the served meal's marinade
    is acidic (found in another task)."""
    h = _rule(text, section, offsets, acidic)
    if h is None:
        return None
    if h.shelf != "freeze_then_thaw":
        return h.shelf, None, h.reason
    first_late = min((o for o in offsets if o is not None and o > h.late_after), default=None)
    return h.shelf, _thaw(first_late, f"Freeze {h.obj}{h.suffix} Sunday"), h.reason


@dataclass
class RuleResult:
    tasks: list[PrepTask]
    day_of: dict[int, list[str]] = field(default_factory=dict)   # entry_id → prep to do on the night
    thaw: dict[int, list[str]] = field(default_factory=dict)     # entry_id → thaw reminders
    warnings: list[str] = field(default_factory=list)


def _is_late(m: MealIn, after: int = LATE) -> bool:
    return m.offset is None or m.offset > after


def _portion(m: MealIn) -> str:
    return m.label if m.day is not None else m.title


def _freeze(res: RuleResult, h: _Hit, known: list[MealIn]) -> str:
    """Record per-night thaw reminders for the late meals; return the task's thaw text (naming portions
    when the task also serves nights that stay in the fridge)."""
    late = [m for m in known if _is_late(m, h.late_after)]
    fresh = [m for m in known if not _is_late(m, h.late_after)]
    for m in late:
        res.thaw.setdefault(m.entry_id, []).append(_thaw(m.offset, f"Freeze {h.obj}{h.suffix} Sunday"))
    if not fresh:
        first = min((m.offset for m in late if m.offset is not None), default=None)
        return _thaw(first, f"Freeze {h.obj}{h.suffix} Sunday")
    of = f" of {h.obj}" if h.obj != "it" else ""
    parts = [_thaw(m.offset, f"Freeze the {_portion(m)} portion{of}{h.suffix} Sunday") for m in late]
    keep = ", ".join(_portion(m) for m in fresh)
    return " ".join(parts) + f" Keep the {keep} portion{'s' if len(fresh) > 1 else ''} in the fridge."


def apply_rules(raw: RawPrepPlan, meals: list[MealIn]) -> RuleResult:
    by_id = {m.entry_id: m for m in meals}
    res = RuleResult(tasks=[], warnings=list(raw.warnings))
    # entries whose marinade is acidic somewhere in the plan (e.g. jarred in "sauces", fish added in "proteins")
    acidic = {i for s in SECTIONS for t in getattr(raw, s) if MARINADE.search(t.text) and ACID.search(t.text)
              for i in t.serves}
    for section in SECTIONS:
        for t in getattr(raw, section):
            text = t.text.strip()
            if not text:
                continue
            known = [by_id[i] for i in dict.fromkeys(t.serves) if i in by_id]
            if t.serves and not known:
                res.warnings.append(f"'{text}' doesn't match any planned meal (entries {t.serves}).")
            if section != "pack" and is_hot(text):
                for m in known:
                    res.day_of.setdefault(m.entry_id, []).append(text)
                res.warnings.append(f"Moved to the cook card — Sunday is prep only: {text}")
                continue
            offsets = [m.offset for m in known]
            shelf, thaw, flags = t.shelf_life, t.thaw, []
            h = _rule(text, section, offsets, acidic=any(m.entry_id in acidic for m in known))
            if h and _RANK[h.shelf] > _RANK[shelf]:
                shelf = h.shelf
                flags.append(h.reason)
                res.warnings.append(f"{text}: {h.reason}")
            if shelf == "freeze_then_thaw":
                if h and h.shelf == "freeze_then_thaw":
                    thaw = _freeze(res, h, known)
                else:   # the LLM's own freeze call
                    late = [o for o in offsets if o is not None and o > LATE]
                    thaw = thaw or _thaw(min(late, default=None), "Freeze it Sunday")
                    for m in known:
                        res.thaw.setdefault(m.entry_id, []).append(thaw)
            else:
                thaw = None
            if shelf == "day_of":
                for m in known:
                    res.day_of.setdefault(m.entry_id, []).append(text)
            contents = _check_contents(res, t.contents, known, flags) if section == "pack" else []
            res.tasks.append(PrepTask(id="", section=section, text=text, serves=[m.serves() for m in known],
                                      est_minutes=t.est_minutes, shelf_life=shelf, thaw=thaw, contents=contents,
                                      flags=flags))
    _normalize_kits(res, meals)
    for section in SECTIONS:
        for n, t in enumerate((t for t in res.tasks if t.section == section), 1):
            t.id = f"{section}-{n}"
    for eid, lines in res.thaw.items():
        res.thaw[eid] = list(dict.fromkeys(lines))
    for m in meals:
        if m.day is None:
            res.warnings.append(f"{m.title} has no night yet — it gets no kit or cook card until it's placed.")
    return res


def _check_contents(res: RuleResult, contents: list[str], known: list[MealIn], flags: list[str]) -> list[str]:
    """Kit items get the same rules: cooked or fragile items move to the night; raw fish/meat for a late
    night stays listed but frozen."""
    out = []
    offsets = [m.offset for m in known]
    for item in contents:
        if COOKED.search(item) or is_hot(item):
            h = _Hit("day_of", "cooked items aren't made on Sunday — moved to the night")
        else:
            h = _rule(item, "proteins", offsets)
        if h is None:
            out.append(item)
            continue
        flags.append(f"{item}: {h.reason}")
        res.warnings.append(f"Kit item '{item}': {h.reason}")
        if h.shelf == "day_of":
            for m in known:
                res.day_of.setdefault(m.entry_id, []).append(item)
        else:
            _freeze(res, h, known)
            out.append(f"{item} (frozen — see thaw)")
    return out


def _normalize_kits(res: RuleResult, meals: list[MealIn]) -> None:
    """Exactly one kit per placed night: split kits spanning nights, merge same-night kits, add entries a
    night's kit missed, synthesize missing kits. Kits are ordered by night."""
    by_id = {m.entry_id: m for m in meals}
    others = [t for t in res.tasks if t.section != "pack"]
    kits: dict[str, PrepTask] = {}
    loose = []
    for t in (t for t in res.tasks if t.section == "pack"):
        nights: dict[str, list] = {}
        for s in t.serves:
            m = by_id[s.entry_id]
            if m.day is not None:
                nights.setdefault(m.date, []).append(s)
        if not nights:
            loose.append(t)
            continue
        split = len(nights) > 1
        if split:
            res.warnings.append(f"Split '{t.text}' into one kit per night — check each kit's contents.")
        for d, ss in nights.items():
            label = by_id[ss[0].entry_id].label
            piece = t.model_copy(update={
                "serves": ss, "text": f"{label.upper()} – {t.text}" if split else t.text,
                "est_minutes": round(t.est_minutes / len(nights)) if split else t.est_minutes,
                "contents": list(t.contents), "flags": list(t.flags)})
            if d not in kits:
                kits[d] = piece
                continue
            k = kits[d]
            res.warnings.append(f"Merged two kits for {label}: '{k.text}' + '{piece.text}'.")
            have = {s.entry_id for s in k.serves}
            k.serves += [s for s in piece.serves if s.entry_id not in have]
            k.contents += [c for c in piece.contents if c not in k.contents]
            k.est_minutes += piece.est_minutes
            k.flags += piece.flags
    nights_meals: dict[str, list[MealIn]] = {}
    for m in meals:
        if m.day is not None:
            nights_meals.setdefault(m.date, []).append(m)
    for d, ms in nights_meals.items():
        label = ms[0].label
        if d not in kits:
            kits[d] = PrepTask(id="", section="pack", text=f"{label.upper()} – {' + '.join(m.title for m in ms)} kit",
                               serves=[], est_minutes=5)
            res.warnings.append(f"Added a kit for {label} (the plan had none).")
            new_kit = True
        else:
            new_kit = False
        k = kits[d]
        for m in ms:
            if m.entry_id in {s.entry_id for s in k.serves}:
                continue
            k.serves.append(m.serves())
            k.contents += [t.text for t in others if t.shelf_life != "day_of" and t.text not in k.contents
                           and m.entry_id in {s.entry_id for s in t.serves}]
            if not new_kit:
                res.warnings.append(f"Added {m.title} to the {label} kit (the plan left it out).")
    res.tasks = others + [kits[d] for d in sorted(kits)] + loose
