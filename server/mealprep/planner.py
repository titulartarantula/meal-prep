"""Purchase planner: the fewest packages of a product that cover a shopping-list need.

The need (the merged list line: qty + unit, units from `ingredients`) and the product's free-text package size
("2 L", "6 x 355 mL", "12 ea", "per kg") are both put into one base: ml (volume), g (weight) or a count. When the
families differ a small, conservative table converts (cups of flour → g, butter sticks → g, onions → g each);
without a confident conversion the plan falls back to 1 pack and `needs_check`. The AI sees `min_packs` as a
hint; the build uses exactly that floor (`default_quantity`), never more: a user who wants spares edits the line.
Where the planner can't compare (`needs_check`) the build buys 1, or the AI's number for a pack sold by the piece.

The tables below are the maintained data; extend them rather than special-casing call sites."""
from dataclasses import dataclass
import math
import re

from .ingredients import BASE, COUNT_UNITS, CONTAINERS, UNITS

ML_PER_TSP = 1 / BASE["ml"][1]          # the list's volume base is the teaspoon
SLACK = 0.02                             # a need within 2% of a pack (rounding, cup ≈ 237 ml) fits in it
MAX_PACKS = 12                           # more than this many packs: likely the wrong product → check

# g per ml, matched on the item name (first hit wins; specific names before general ones). Values lean high
# where recipes vary, so a conversion errs towards covering the need.
DENSITY = [
    (r"sugar snap|snap pea|rice noodle|rice paper|cream cheese|ice cream|cream of", None),
    (r"rice vinegar|rice wine", 1.0), (r"peanut butter|almond butter", 1.08), (r"buttermilk", 1.03),
    (r"butter|margarine|shortening|lard", 0.96), (r"brown sugar", 0.93),
    (r"icing sugar|powdered sugar|confectioners", 0.53), (r"sugar", 0.85), (r"cocoa", 0.45), (r"flour", 0.53),
    (r"oats|oatmeal", 0.4), (r"rice", 0.85), (r"orzo|couscous|quinoa|lentil|split pea", 0.85),
    (r"bread ?crumb|panko", 0.45), (r"yeast", 0.65), (r"honey", 1.42), (r"maple syrup|corn syrup|molasses", 1.37),
    (r"oil", 0.92), (r"chocolate chip|chocolate", 0.72), (r"yogh?urt|sour cream", 1.05),
    (r"milk|cream|half[- ]and[- ]half", 1.03), (r"water|broth|stock|juice|vinegar|wine|soy sauce", 1.0),
    (r"cumin|paprika|chil+i powder|cinnamon|turmeric|curry powder|garam masala|nutmeg|allspice|cayenne", 0.55),
    (r"sage|parsley|cilantro|basil|mint|dill|chives|thyme|rosemary|oregano|tarragon", 0.25),   # fresh, chopped
    (r"walnut|pecan|almond|cashew|peanut|pine nut|hazelnut|pistachio", 0.6),
]
# Weight-sensitive items whose density depends on the cut ("shredded cheese", "chopped onion").
DENSITY_PREP = [(r"cheese|cheddar|mozzarella|parmesan|parmigiano", r"shred|grat", 0.45),
                (r"onion", r"chop|dic|mince|slic", 0.67)]
# g per piece for produce bought by the piece but sold in bags (heavier than average → errs to cover).
EACH_G = [(r"sweet potato|yam", 300), (r"potato", 220), (r"onion", 200), (r"shallot", 40), (r"lemon", 120),
          (r"lime", 70), (r"orange", 200), (r"apple", 200), (r"carrot", 75), (r"tomato", 150),
          (r"bell pepper", 180), (r"zucchini", 250), (r"avocado", 200), (r"cucumber", 300), (r"banana", 130)]
# Part of a bought item: unit → (parts per item, g per part or None).
PARTS = {"clove": (8, 5), "sprig": (10, None), "stalk": (6, 40)}
STICK_G = 113                           # a stick of butter
NEGLIGIBLE = {"pinch", "dash", "handful", "knob"}
WHOLE = set(COUNT_UNITS.values()) - CONTAINERS - set(PARTS) - NEGLIGIBLE   # bunch, head, ear, slice, fillet …

_NUM = r"\d+(?:\.\d+)?"
_PACK_UNITS = {**{k: v for k, v in UNITS.items() if v in BASE and len(k) > 1 or k in {"g", "l"}},
               "cl": "cl", "fl oz": "fl oz", "floz": "fl oz"}
_COUNT_WORDS = r"ea|each|count|ct|pack|pk|pcs?|pieces?|units?|eggs?|rolls?|bags?|bars?|cans?|bottles?"
_BY_WEIGHT = re.compile(r"^\s*(?:per|/)\s*(?:\d+\s*)?(?:kg|g|lb)\b|/\s*(?:\d+\s*)?(?:kg|g|lb)\b|priced by weight|"
                        r"sold by weight|\bavg\b|\bapprox", re.I)


_TAIL = r"(?: [a-z][a-z -]*)?"            # "1.36 kg bag", "2 l carton": words after the size


def _unit(word: str) -> str | None:
    return _PACK_UNITS.get(re.sub(r"[. ]", "", word).replace("floz", "fl oz"))


@dataclass
class Pack:
    family: str | None = None            # "vol" (ml), "mass" (g), "count", or None when unknown
    amount: float | None = None          # per item, in the family's base
    units: int = 1                       # items in a multipack ("6 x 355 mL" → 6)
    by_weight: bool = False              # weighed at the till: the quantity can't be planned
    priced_by_weight: bool = False       # sold by the piece, charged by weight (a red onion)
    label: str = "?"

    @property
    def total(self) -> float | None:
        return None if self.amount is None else self.amount * self.units


def _to_base(n: float, unit: str):
    if unit == "cl":
        return "vol", n * 10
    fam, size = BASE[unit]
    return fam, (n * size * ML_PER_TSP if fam == "vol" else n * size)


def _num_label(n: float) -> str:
    return f"{n:g}"


def _unit_label(u: str) -> str:
    return {"l": "L", "ml": "mL", "cl": "cL"}.get(u, u)


def parse_pack(text: str | None, sold_by: str | None = None) -> Pack:
    """Package size → Pack. Never raises; anything unreadable is family None (and needs a check)."""
    s = re.sub(r"\s+", " ", (text or "").replace("×", "x").replace(",", ".")).strip().lower()
    st = (sold_by or "").upper()
    if st == "SOLD_BY_WEIGHT" or (s and _BY_WEIGHT.search(s)):
        return Pack(by_weight=True, label=f"{text.strip()} (priced by weight)" if s else "priced by weight")
    try:
        m = re.fullmatch(rf"(\d+) ?x ?({_NUM}) ?(fl\.? ?oz|[a-z]+)\.?{_TAIL}", s)
        if m and _unit(m.group(3)):
            u = _unit(m.group(3))
            fam, amt = _to_base(float(m.group(2)), u)
            return Pack(fam, amt, int(m.group(1)), label=f"{m.group(1)} × {_num_label(float(m.group(2)))} {_unit_label(u)}")
        m = re.fullmatch(rf"({_NUM}) ?(fl\.? ?oz|[a-z]+)\.?{_TAIL}", s)
        if m and _unit(m.group(2)):
            u = _unit(m.group(2))
            fam, amt = _to_base(float(m.group(1)), u)
            return Pack(fam, amt, label=f"{_num_label(float(m.group(1)))} {_unit_label(u)}")
        m = re.fullmatch(rf"(?:(\d+) )?dozen", s)
        if m:
            n = 12 * int(m.group(1) or 1)
            return Pack("count", n, label=f"{n} ea")
        m = re.fullmatch(rf"(?:(\d+) ?)?(?:{_COUNT_WORDS})\.?", s)
        if m and (m.group(1) or s in {"ea", "each"}):
            n = int(m.group(1) or 1)
            return Pack("count", n, label=f"{n} ea")
    except (ValueError, KeyError):
        pass
    if not s and st == "SOLD_BY_EACH_PRICED_BY_WEIGHT":
        return Pack("count", 1, priced_by_weight=True, label="1 ea (priced by weight)")
    return Pack(label=text.strip() if s else "?")


def _match(table, name: str):
    return next((v for pat, v in table if re.search(rf"\b(?:{pat})(?:e?s)?\b", name)), None)


def density(it) -> float | None:
    """g per ml for this list item, or None when there's no confident figure."""
    name, prep = (it.name or "").lower(), (it.prep or "").lower()
    for pat, prep_pat, d in DENSITY_PREP:
        if re.search(rf"\b(?:{pat})(?:e?s)?\b", name) and re.search(prep_pat, f"{name} {prep}"):
            return d
    return _match(DENSITY, name)


# --- needs ------------------------------------------------------------------------------------------------------

_KITCHEN = [(1 / 8, "⅛"), (1 / 6, "⅙"), (1 / 4, "¼"), (1 / 3, "⅓"), (3 / 8, "⅜"), (1 / 2, "½"), (5 / 8, "⅝"),
            (2 / 3, "⅔"), (3 / 4, "¾"), (5 / 6, "⅚"), (7 / 8, "⅞")]
_METRIC = {"g", "kg", "ml", "l"}


def _qty_text(q: float, unit: str | None) -> str:
    if unit in _METRIC:
        return f"{round(q, 2):g}"
    whole, frac = int(q), q - int(q)
    if frac < 0.02:
        return str(whole)
    if frac > 0.98:
        return str(whole + 1)
    glyph = next((g for f, g in _KITCHEN if abs(frac - f) <= 0.02), None)
    if glyph is None:
        return f"{round(q, 2):g}"
    return f"{whole}{glyph}" if whole else glyph


def _plural(unit: str, q: float) -> str:
    if q <= 1 or unit not in COUNT_UNITS.values() and unit != "cup":
        return unit
    return unit + ("es" if unit.endswith(("ch", "sh", "x")) else "s")


def need_text(it) -> str:
    q = _qty_text(it.qty, it.unit)
    return f"{q} {_plural(it.unit, it.qty)}" if it.unit else q


def _approx(n: float, unit: str) -> str:
    n = round(n, -1) if n >= 100 else round(n)
    return f"≈ {n:g} {unit}"


# --- plans --------------------------------------------------------------------------------------------------------

@dataclass
class Plan:
    packs_min: int
    enforce: bool                        # the floor is reliable: quantity must be at least packs_min
    needs_check: bool                    # the planner couldn't confirm the packs cover the need
    need: str | None                     # "⅚ cup", "5 cups (≈ 660 g)"; None when the list gave no amount
    pack: str                            # "1 L", "6 × 355 mL"
    note: str | None = None              # why it needs a check

    def why(self, quantity: int | None) -> str:
        q = quantity if quantity else self.packs_min
        head = f"Need {self.need}" if self.need else "No amount given"
        out = f"{head} → {q} × {self.pack}"
        if self.note:
            out += f" (check: {self.note})"
        elif self.enforce and q < self.packs_min:
            out += " (short)"
        elif self.enforce and q > self.packs_min:
            out += f" (you chose {q})"
        return out


def _get(product, field):
    return product.get(field) if isinstance(product, dict) else getattr(product, field, None)


def _packs(need: float, per_pack: float) -> int:
    return max(1, math.ceil(need * (1 - SLACK) / per_pack - 1e-9))


def plan(it, product) -> Plan | None:
    """The plan for one list item and one product (a Product or a line's product dict); None without a product
    (or, defensively, if anything in the data is too odd to plan: a draft must always render)."""
    if not product:
        return None
    try:
        return _plan_line(it, product)
    except Exception:
        return None


def _packs_text(n: int) -> str:
    return f"{n} pack{'' if n == 1 else 's'}"


def _plan_line(it, product) -> Plan:
    sp = getattr(it, "staple_packs", None)
    if sp:
        return _plan_staple(it, product, sp)
    pack = parse_pack(_get(product, "package_size"), _get(product, "sold_by"))
    if it.qty is None or it.qty <= 0:
        return Plan(1, True, False, None, pack.label)
    unit, name = it.unit, (it.name or "").lower()
    text = need_text(it)

    def unsure(note):
        return Plan(1, False, True, text, pack.label, note)

    if pack.by_weight:
        return unsure("priced by weight")
    if unit in NEGLIGIBLE:
        return Plan(1, True, False, text, pack.label)
    if pack.family is None:
        return unsure("package size unknown")

    need, fam, shown = None, None, text                     # the need in the pack's base
    if unit in BASE:
        fam, need = _to_base(it.qty, unit)
        if fam != pack.family and pack.family in {"vol", "mass"}:
            d = density(it)
            if d is None:
                return unsure(f"can't compare {'volume' if fam == 'vol' else 'weight'} with "
                              f"{'weight' if fam == 'vol' else 'volume'}")
            need, fam = (need * d, "mass") if fam == "vol" else (need / d, "vol")
            shown = f"{text} ({_approx(need, 'g' if fam == 'mass' else 'ml')})"
    elif unit == "stick" and re.search(r"\bbutter|margarine", name) and pack.family == "mass":
        fam, need = "mass", it.qty * STICK_G
        shown = f"{text} ({_approx(need, 'g')})"
    elif unit in PARTS:
        per, grams = PARTS[unit]
        if pack.family == "count":
            fam, need = "count", it.qty / per
        elif pack.family == "mass" and grams:
            fam, need = "mass", it.qty * grams
    elif unit in CONTAINERS:
        if pack.family in {"vol", "mass"}:                  # one can/jar/bottle per item in the pack
            return _plan(it.qty, pack.units, text, pack)
        fam, need = "count", it.qty
    elif unit is None or unit in WHOLE:
        if pack.family == "count":
            fam, need = "count", it.qty
        elif unit is None and pack.family == "mass" and (g := _match(EACH_G, name)):
            fam, need = "mass", it.qty * g
            shown = f"{text} ({_approx(need, 'g')})"
    if pack.priced_by_weight and fam != "count":
        return unsure("priced by weight")
    if need is None or fam != pack.family:
        return unsure(f"can't compare {unit or 'pieces'} with {pack.label}")
    return _plan(need, pack.total, shown, pack)


def _plan_staple(it, product, sp: int) -> Plan:
    """A weekly staple counted in packs is a floor: on its own it is exactly that many packs; merged with a recipe
    amount the line covers the amount and is at least that many packs (`shopping.build_list`)."""
    base = _plan_line(it.model_copy(update={"staple_packs": None}), product)
    if base.need is None:
        return Plan(sp, True, False, f"{_packs_text(sp)} (weekly staple)", base.pack)
    return Plan(max(base.packs_min, sp), True, base.needs_check, f"{base.need}; staple: at least {_packs_text(sp)}",
                base.pack, base.note)


def _plan(need: float, per_pack: float, shown: str, pack: Pack) -> Plan:
    n = _packs(need, per_pack)
    if n > MAX_PACKS:
        return Plan(n, False, True, shown, pack.label, f"{n} packs seems a lot")
    return Plan(n, True, False, shown, pack.label)


def floor_quantity(it, product, quantity: int | None) -> int:
    """A quantity the user chose, raised to the reliable floor (never below 1, never reduced)."""
    q = max(1, int(quantity or 0))
    p = plan(it, product)
    return max(q, p.packs_min) if p and p.enforce else q


def _int(q) -> int:
    try:
        return int(q or 0)
    except (TypeError, ValueError, OverflowError):
        return 0


def default_quantity(it, product, suggested=None) -> int:
    """The quantity the build puts on an AI or remembered line (user edits are kept elsewhere).

    Reliable floor → exactly that floor, never more. Otherwise 1, except for a pack sold by the piece (onions
    each, a 3-count of garlic) where the suggested number is kept if it is plausible (1–MAX_PACKS)."""
    p = plan(it, product)
    if p is None:
        return 1
    if p.enforce:
        return p.packs_min
    q = _int(suggested)
    if 1 <= q <= MAX_PACKS and parse_pack(_get(product, "package_size"), _get(product, "sold_by")).family == "count":
        return q
    return 1


def _scale(qty: int, need, unit, it) -> int | None:
    """Last purchase scaled to this week's need, in the planner's base units; None when they can't be compared."""
    if it.qty is None or not need:
        return qty
    if unit == it.unit:
        ratio = it.qty / need
    elif unit in BASE and it.unit in BASE and BASE[unit][0] == BASE[it.unit][0]:
        ratio = _to_base(it.qty, it.unit)[1] / _to_base(need, unit)[1]
    else:
        return None
    return max(1, math.ceil(qty * ratio - 1e-9))


def remembered_quantity(it, product, last) -> int:
    """The quantity for a remembered pick. `last` = (need_qty, quantity, need_unit) of its last sent line.

    With a reliable floor the history is ignored (the floor, as for an AI pick): a one-off edit, or a
    quantity from before the planner, never becomes the household's normal amount. Without one, the
    history counts only for a pack sold by the piece, scaled by the need, and only if it is sane: more than
    2 × max(1, packs_min) is stale or a one-off and is ignored."""
    p = plan(it, product)
    if p is None or p.enforce or not last:
        return default_quantity(it, product)
    need, qty, unit = last
    qty, cap = max(1, _int(qty)), 2 * max(1, p.packs_min)
    scaled = _scale(qty, need, unit, it) if qty <= cap else None
    if scaled is None or scaled > cap:
        return default_quantity(it, product)
    return default_quantity(it, product, scaled)


def min_packs(it, cands) -> dict[str, int]:
    """{code: fewest packs that cover the need} for the AI prompt (1 where the planner can't tell)."""
    out = {}
    for c in cands:
        p = plan(it, c)
        out[_get(c, "code")] = p.packs_min if p and p.enforce else 1
    return out
