"""Deterministic ingredient clean-up, applied after the AI structures a line and again when the list is built.

- `parse_amount` reads the amount and unit at the start of the raw line ("⅓ cup plus 1 tablespoon/75 ml …").
- `split_prep` moves preparation/state words out of the name into a prep note ("whole milk, warmed" →
  "whole milk" + "warmed"), so the name is what you buy: it is the merge key and the product search term.
- `clean_ingredient` uses both to repair what the model returned (a dropped unit, a lost "plus 1 tablespoon",
  prep left in the name). It never empties a name or raises on odd input, and is idempotent.

The word lists below are the maintained data; extend them rather than special-casing call sites."""
from dataclasses import dataclass, field
from fractions import Fraction
import re

from .models import Ingredient

# Convertible units: (dimension, size in the dimension's base unit: tsp / g).
BASE = {"tsp": ("vol", 1), "tbsp": ("vol", 3), "cup": ("vol", 48), "fl oz": ("vol", 6), "ml": ("vol", 0.2029),
        "l": ("vol", 202.9),
        "g": ("mass", 1), "kg": ("mass", 1000), "oz": ("mass", 28.35), "lb": ("mass", 453.6)}

VULGAR = {"½": "1/2", "⅓": "1/3", "⅔": "2/3", "¼": "1/4", "¾": "3/4", "⅕": "1/5", "⅖": "2/5", "⅗": "3/5", "⅘": "4/5",
          "⅙": "1/6", "⅚": "5/6", "⅛": "1/8", "⅜": "3/8", "⅝": "5/8", "⅞": "7/8"}

UNITS = {"teaspoon": "tsp", "teaspoons": "tsp", "tsp": "tsp", "tsps": "tsp", "t": "tsp",
         "tablespoon": "tbsp", "tablespoons": "tbsp", "tbsp": "tbsp", "tbsps": "tbsp", "tb": "tbsp", "tbs": "tbsp",
         "tbl": "tbsp", "cup": "cup", "cups": "cup", "c": "cup",
         "gram": "g", "grams": "g", "g": "g", "gr": "g", "kilogram": "kg", "kilograms": "kg", "kg": "kg",
         "kgs": "kg", "ml": "ml", "milliliter": "ml", "milliliters": "ml", "millilitre": "ml", "millilitres": "ml",
         "l": "l", "liter": "l", "liters": "l", "litre": "l", "litres": "l",
         "pound": "lb", "pounds": "lb", "lb": "lb", "lbs": "lb", "ounce": "oz", "ounces": "oz", "oz": "oz"}
# Count units: kept as written (singular), never converted or merged with volume/mass.
COUNT_UNITS = {"clove": "clove", "cloves": "clove", "can": "can", "cans": "can", "tin": "tin", "tins": "tin",
               "jar": "jar", "jars": "jar", "package": "package", "packages": "package", "pkg": "package",
               "packet": "packet", "packets": "packet", "bag": "bag", "bags": "bag", "box": "box", "boxes": "box",
               "bottle": "bottle", "bottles": "bottle", "carton": "carton", "cartons": "carton",
               "container": "container", "containers": "container", "bunch": "bunch", "bunches": "bunch",
               "sprig": "sprig", "sprigs": "sprig", "stalk": "stalk", "stalks": "stalk", "head": "head",
               "heads": "head", "slice": "slice", "slices": "slice", "stick": "stick", "sticks": "stick",
               "pinch": "pinch", "pinches": "pinch", "dash": "dash", "dashes": "dash", "piece": "piece",
               "pieces": "piece", "handful": "handful", "handfuls": "handful", "sheet": "sheet", "sheets": "sheet",
               "ear": "ear", "ears": "ear", "fillet": "fillet", "fillets": "fillet", "knob": "knob"}
CONTAINERS = {"can", "tin", "jar", "package", "packet", "bag", "box", "bottle", "carton", "container"}
# Words in front of an amount: kept as a note ("scant 1/2 cup sugar" → prep "scant").
QUALIFIERS = {"scant", "heaping", "heaped", "generous", "about", "approximately", "approx", "roughly", "around",
              "rounded", "level"}

# Preparation / state words (past participles and the like): never part of what you buy.
PARTICIPLES = {
    "warmed", "heated", "melted", "softened", "chopped", "diced", "minced", "sliced", "grated", "shredded", "drained",
    "rinsed", "thawed", "defrosted", "peeled", "seeded", "deseeded", "cored", "pitted", "stemmed", "crushed",
    "toasted", "beaten", "whisked", "cooled", "chilled", "trimmed", "halved", "quartered", "cubed", "zested",
    "juiced", "mashed", "packed", "sifted", "torn", "julienned", "smashed", "scrubbed", "cleaned", "washed",
    "separated", "divided", "deveined", "shelled", "hulled", "squeezed", "patted", "shaved", "crumbled",
    "broken", "snipped", "cooked", "uncooked", "roasted", "boiled", "steamed", "blanched", "reserved", "cut",
    "slivered", "skinned", "boned", "scored", "pounded", "flattened", "ripped", "segmented", "spiralized",
    "lukewarm", "warm", "cold", "chilled", "frozen-thawed", "room-temperature", "optional",
}
ADVERBS = {"finely", "roughly", "coarsely", "thinly", "thickly", "freshly", "lightly", "firmly", "loosely", "very",
           "well", "gently", "evenly", "barely", "just", "freshly-ground", "newly"}
STATE = {"fresh", "small", "medium", "large", "extra-large", "jumbo"}   # also stripped into prep
# Product-changing words: always kept in the name (listed for clarity and for the raw-line name recovery).
KEEP = {"frozen", "canned", "dried", "smoked", "unsalted", "salted", "whole", "skim", "2%", "1%", "ground",
        "low-sodium", "reduced-sodium", "no-salt-added", "sweetened", "unsweetened", "light", "dark", "raw",
        "plain", "organic", "boneless", "skinless", "bone-in", "skin-on", "extra-virgin", "virgin", "instant",
        "rolled", "quick", "kosher", "sea", "baby", "sun-dried", "pickled", "cured", "evaporated", "condensed"}
# Product names that start with a participle ("toasted sesame oil"): never stripped.
PRODUCT_PHRASES = ("toasted sesame oil", "crushed red pepper", "roasted red pepper", "sliced almond",
                   "slivered almond", "whipped cream", "whipped topping", "shredded coconut", "shredded wheat",
                   "crushed pineapple", "cooked ham")
# Canned products named by their cut: kept only when bought as a can/jar/carton ("1 can diced tomatoes").
CANNED_PHRASES = ("diced tomato", "crushed tomato", "chopped tomato", "sliced peach", "sliced mushroom",
                  "chopped clam", "diced green chile", "chopped green chile")
_TRAILING = re.compile(
    r"\s*\b(to taste|for (?:serving|garnish(?:ing)?|dusting|greasing|brushing|sprinkling|drizzling|frying|"
    r"decorat(?:ing|ion)|topping|the (?:pan|pans|dish|top|bowl|baking sheet)|the [a-z]+)\b.*|"
    r"plus (?:more|extra|additional)\b.*|divided|optional|if (?:desired|needed|using|you like|available)\b.*|"
    r"as needed|or (?:substitute|more|less|to taste|as needed)\b.*|(?:at |to )?room temperature)$")
_LEADING_PHRASES = ("at room temperature", "room temperature", "room-temperature")
_LINKERS = {"and", "or", "then", "&"}
_TAIL_STARTERS = {"to", "until", "into", "in", "with", "for", "at", "and", "then", "lengthwise", "crosswise",
                  "on", "from", "if", "about", "as"}

_NUM = r"(?:\d+\s+\d+/\d+|\d+/\d+|\d*\.\d+|\d+)"
_AMOUNT = re.compile(rf"(?P<n>{_NUM})(?:\s*(?:-|–|—|to|or)\s*(?P<n2>{_NUM}))?")
_PAREN = re.compile(r"\(([^()]*)\)")
_SIZE = re.compile(rf"{_NUM}\s*-?\s*(?:ounce|oz|pound|lb|gram|g|ml|milliliter|millilitre|liter|litre|inch|in|cm)s?\b\.?",
                   re.I)


def _num(tok: str) -> float | None:
    try:
        return float(sum(Fraction(p) for p in tok.split()))
    except (ValueError, ZeroDivisionError):
        return None


def _prep_text(s: str) -> str:
    for k, v in VULGAR.items():
        s = s.replace(k, f" {v}")
    return re.sub(r"\s+", " ", s.replace("⁄", "/").replace(" ", " ")).strip()


@dataclass
class Amount:
    qty: float | None = None
    unit: str | None = None          # "cup", "g", "clove", "can", or None for a plain count
    rest: str = ""                   # the line after the amount: "black beans, drained"
    notes: list[str] = field(default_factory=list)   # "14 oz" package size, "scant"
    compound: bool = False           # "… plus 1 tablespoon" / "… minus 2 tablespoons" was added in


def _read_amount(s: str):
    """(qty, rest) for a number or range at the start of s (upper bound of a range), else (None, s)."""
    m = _AMOUNT.match(s)
    if not m:
        return None, s
    hi = _num(m.group("n2")) if m.group("n2") else None
    q = _num(m.group("n"))
    if q is None:
        return None, s
    return (max(q, hi) if hi is not None else q), s[m.end():].lstrip()


def _read_unit(s: str):
    """(unit, rest) for a unit word at the start of s ("cups", "tbsp.", "fl oz"), else (None, s)."""
    m = re.match(r"(fl\.?\s*oz\.?|fluid\s+ounces?)\b", s, re.I)
    if m:
        return "fl oz", s[m.end():].lstrip()
    m = re.match(r"([A-Za-z]+)\.?(?![\w-])", s)
    if not m:
        return None, s
    w = m.group(1)
    u = "tbsp" if w == "T" else UNITS.get(w.lower()) or COUNT_UNITS.get(w.lower())
    if u is None:
        return None, s
    return u, s[m.end():].lstrip()


def parse_amount(raw: str) -> Amount:
    """Amount at the start of an ingredient line. Unicode fractions, mixed numbers, ranges (upper bound),
    "scant"/"heaping", package sizes ("2 (14 oz) cans", "1 15-ounce can"), "plus/minus N unit", metric
    alternatives after a slash ("2 tablespoons/16 grams"). Unparseable input gives Amount(rest=raw)."""
    a = Amount(rest=(raw or "").strip())
    try:
        s = _prep_text(raw or "")
        words = s.split(" ", 1)
        if words and words[0].lower().rstrip(".") in QUALIFIERS and len(words) > 1 and re.match(r"\d", words[1]):
            a.notes.append(words[0].lower().rstrip("."))
            s = words[1]
        q, s = _read_amount(s)
        if q is None:
            if re.match(r"(?:a|an|one)\s", s, re.I):   # "a pinch of salt", "an 8-ounce block"
                u, rest = _read_unit(s.split(" ", 1)[1])
                if u:
                    a.qty, a.unit, a.rest = 1.0, u, re.sub(r"^of\s+", "", rest)
            return a
        # package size before the unit: "2 (14 oz) cans", "1 15-ounce can"
        m = _PAREN.match(s)
        if m and _SIZE.search(m.group(1)):
            after = s[m.end():].lstrip()
            u, _ = _read_unit(after)
            if u in CONTAINERS or u is None:
                a.notes.append(m.group(1).strip())
                s = after
        m = _SIZE.match(s)
        if m:
            u, _ = _read_unit(s[m.end():].lstrip())
            if u in CONTAINERS:
                a.notes.append(m.group(0).strip())
                s = s[m.end():].lstrip()
        unit, s = _read_unit(s)
        # "⅓ cup plus 1 tablespoon", "2 cups minus 2 tablespoons"
        while unit in BASE:
            m = re.match(r"(plus|\+|minus|less)\s+", s, re.I)
            if not m:
                break
            q2, s2 = _read_amount(s[m.end():])
            u2, s2 = _read_unit(s2) if q2 is not None else (None, s2)
            if u2 not in BASE or BASE[u2][0] != BASE[unit][0]:
                break
            sign = -1 if m.group(1).lower() in {"minus", "less"} else 1
            q += sign * q2 * BASE[u2][1] / BASE[unit][1]
            s, a.compound = s2, True
        if unit is not None:
            # metric/imperial alternative: "/16 grams", "(120 ml)" right after the amount
            m = re.match(rf"/\s*{_NUM}\s*[A-Za-z]+\.?\s*", s)
            if m:
                s = s[m.end():]
            s = re.sub(r"^of\s+", "", s)
        a.qty, a.unit, a.rest = (q if q > 0 else None), unit, s.strip()
    except Exception:   # odd input: keep the line as written
        return Amount(rest=(raw or "").strip())
    return a


# --- prep words ---------------------------------------------------------------------------------------------

def _starts_with_phrase(text: str, phrases) -> bool:
    return any(re.match(rf"{re.escape(p)}", text) for p in phrases if p)


def _strip_leading(tokens: list[str], unit: str | None):
    """Leading prep words → (prep groups, remaining tokens). "finely chopped fresh dill" → ["finely chopped", "fresh"]."""
    groups, cur = [], []
    i = 0
    while i < len(tokens) - 1:   # always leave at least one token
        rest = " ".join(tokens[i:])
        if _starts_with_phrase(rest, PRODUCT_PHRASES) or (
                (unit in CONTAINERS) and _starts_with_phrase(rest, CANNED_PHRASES)):
            break
        phrase = next((p for p in _LEADING_PHRASES if rest.startswith(p + " ")), None)
        if phrase:
            n = len(phrase.split())
            if i + n >= len(tokens):
                break
            if cur:
                groups.append(" ".join(cur)); cur = []
            groups.append(phrase.replace("-", " "))
            i += n
            continue
        t = tokens[i]
        nxt = tokens[i + 1]
        if t in ADVERBS and (nxt in PARTICIPLES or nxt == "ground" or nxt in ADVERBS):
            cur.append(t)
            if nxt == "ground":          # "freshly ground black pepper": ground is prep here
                groups.append(" ".join(cur + [nxt])); cur = []
                i += 1
        elif t in PARTICIPLES:
            cur.append(t)
            if i + 2 < len(tokens) and nxt in _LINKERS and (tokens[i + 2] in PARTICIPLES or tokens[i + 2] in ADVERBS):
                cur.append(nxt); i += 1
            else:
                groups.append(" ".join(cur)); cur = []
        elif t in STATE:
            if cur:
                groups.append(" ".join(cur)); cur = []
            groups.append(t)
        else:
            break
        i += 1
    if cur:   # dangling adverb ("very ripe"): put it back
        i -= len(cur)
    return groups, tokens[i:]


def _strip_trailing(tokens: list[str]):
    """A participle after the noun: "oat milk warmed to 110 degrees" → ("warmed to 110 degrees", ["oat", "milk"])."""
    for i in range(1, len(tokens)):
        t = tokens[i]
        if t in PARTICIPLES and (i == len(tokens) - 1 or tokens[i + 1] in _TAIL_STARTERS):
            j = i
            while j > 1 and tokens[j - 1] in ADVERBS:
                j -= 1
            return " ".join(tokens[j:]), tokens[:j]
    return None, tokens


def split_prep(name: str, unit: str | None = None) -> tuple[str, str | None]:
    """(what you buy, prep note or None). Lowercases; keeps product words (frozen, canned, ground beef …)."""
    original = re.sub(r"\s+", " ", (name or "").strip().lower())
    s = _prep_text(original.replace("’", "'"))
    preps: list[tuple[int, str]] = []          # (position, text) — prep reads in the order written
    for m in _PAREN.finditer(s):
        if m.group(1).strip():
            preps.append((m.start(), m.group(1).strip()))
    s = _PAREN.sub(" ", s).replace("(", " ").replace(")", " ")
    s = re.sub(r"\s+", " ", s).strip()
    head, _, tail = s.partition(",")
    head = head.strip()
    if tail.strip(" ,"):
        preps.append((len(head) + 1, re.sub(r"\s+,", ",", tail.strip(" ,"))))
    m = _TRAILING.search(head)
    if m and m.start() > 0:
        preps.append((m.start() + 0.5, m.group(0).strip()))
        head = head[:m.start()].strip()
    tokens = head.split()
    lead, tokens = _strip_leading(tokens, unit)
    for k, g in enumerate(lead):
        preps.append((-100 + k, g))
    trail, tokens = _strip_trailing(tokens)
    if trail:
        preps.append((len(head), trail))
    clean = " ".join(tokens).strip(" -:;,.")
    if not clean:
        return original, None
    prep = ", ".join(p for _, p in sorted(preps, key=lambda x: x[0]) if p) or None
    return clean, prep


# --- whole ingredient ----------------------------------------------------------------------------------------

def _join_prep(*parts: str | None) -> str | None:
    out, seen = [], set()
    for p in parts:
        for piece in re.split(r",\s*", p or ""):
            piece = piece.strip(" ;")
            if piece and piece.lower() not in seen:
                seen.add(piece.lower()); out.append(piece)
    return ", ".join(out) or None


_COMPOUND_NOTE = re.compile(rf"\b(?:plus|minus|less)\s+{_NUM}\s*[a-z]+\.?", re.I)


def clean_ingredient(ing: Ingredient) -> Ingredient:
    """Repair the model's structured line from its raw text; see the module docstring."""
    try:
        return _clean(ing)
    except Exception:   # never lose an ingredient over clean-up
        return ing


def _clean(ing: Ingredient) -> Ingredient:
    amt = parse_amount(ing.raw)
    qty, unit, prep = ing.qty, ing.unit, ing.prep
    if amt.qty is not None:
        same_dim = unit in BASE and amt.unit in BASE and BASE[unit][0] == BASE[amt.unit][0]
        if unit is None and amt.unit is not None and (qty is None or amt.compound or abs(qty - _lead(ing.raw)) < 1e-6):
            qty, unit = amt.qty, amt.unit        # the model dropped the unit ("⅓ cup plus 1 tablespoon …")
        elif amt.compound and same_dim:
            qty, unit = amt.qty * BASE[amt.unit][1] / BASE[unit][1], unit
        elif qty is None and unit is None:
            qty = amt.qty
        if amt.compound and prep:
            prep = _COMPOUND_NOTE.sub("", _prep_text(prep)).strip(" ,;")
            prep = re.sub(r"^\((.*)\)$", r"\1", prep).strip() or None
    name, notes = (ing.name or "").strip(), []
    if not name or name.lower() == (ing.raw or "").lower().strip() or (
            re.match(r"[\d½⅓⅔¼¾⅛⅜⅝⅞]", name) and not re.match(r"[\d.]+\s*%", name)):
        line = parse_amount(name or ing.raw)       # the model's fallback: the whole line as the name
        name, notes = line.rest or name or ing.raw, line.notes
    clean, extra = split_prep(name, unit)
    # The model sometimes drops a product word the line has ("3 tablespoons unsalted butter" → "butter").
    raw_words = split_prep(amt.rest, unit)[0].split(" or ")[-1].split()
    words = clean.split()
    if words and len(raw_words) > len(words) and _singular(raw_words[-1]) == _singular(words[-1]) \
            and raw_words[-len(words):-1] == words[:-1] and all(w in KEEP for w in raw_words[:-len(words)]):
        clean = " ".join(raw_words[:-len(words)] + words)
    if not clean.strip():
        clean = (ing.name or ing.raw or "").strip().lower() or "item"
    return ing.model_copy(update={"name": clean, "qty": qty, "unit": unit,
                                  "prep": _join_prep(extra, prep, *notes)})


def _lead(raw: str) -> float:
    q, _ = _read_amount(_prep_text(raw or ""))
    return q if q is not None else float("nan")


# --- pantry: what most kitchens keep (the deterministic stand-in for the AI's likely_on_hand) -----------------

PANTRY = {"salt", "pepper", "black pepper", "white pepper", "ground pepper", "ground black pepper", "peppercorn",
          "black peppercorn", "water", "ice", "baking soda", "baking powder", "vanilla", "vanilla extract",
          "cornstarch", "honey", "soy sauce", "vinegar", "cooking spray", "garlic powder", "onion powder",
          "chili powder", "chile powder", "paprika", "smoked paprika", "cayenne", "cayenne pepper",
          "red pepper flake", "crushed red pepper", "crushed red pepper flake", "bay leaf", "dried bay leaf",
          "curry powder", "italian seasoning", "dijon mustard", "ketchup", "mayonnaise"}
PANTRY_HEADS = {"salt", "flour", "sugar", "oil", "butter", "egg", "vinegar"}   # "kosher salt", "olive oil" …
SPICES = {"cumin", "cinnamon", "nutmeg", "ginger", "coriander", "clove", "allspice", "cardamom", "turmeric",
          "oregano", "thyme", "basil", "rosemary", "sage", "dill", "marjoram", "tarragon", "parsley", "mint",
          "fennel seed", "cumin seed", "mustard seed", "chili flake", "red pepper", "black pepper"}


def likely_on_hand(name: str) -> bool:
    """Pantry staples (the AI prompt's rule: salt, pepper, oil, common dried spices, flour, sugar, butter, eggs …).
    Fresh herbs and produce are not: "dried oregano" / "ground cumin" are, "red bell pepper" isn't."""
    words = re.sub(r"[\s-]+", " ", (name or "").lower()).strip().split()
    while len(words) > 1 and (words[0] in STATE or words[0] in ADVERBS or words[0] in {"fine", "coarse"}):
        words = words[1:]
    if not words:
        return False
    words[-1] = _singular(words[-1])
    n = " ".join(words)
    if n in PANTRY or words[-1] in PANTRY_HEADS:
        return True
    if words[0] in ("dried", "ground") and " ".join(words[1:]) in SPICES:
        return True
    return n in SPICES and n not in {"basil", "parsley", "mint", "dill", "red pepper"}   # bare herbs are usually fresh


# --- merge keys -------------------------------------------------------------------------------------------------

_PLURAL_KEEP = {"molasses", "hummus", "couscous", "asparagus", "swiss", "grits", "greens", "oats", "brussels",
                "schnapps", "series", "species", "jus"}


def _singular(w: str) -> str:
    if w in _PLURAL_KEEP or len(w) <= 3 or w.endswith(("ss", "us", "is")):
        return w
    if w.endswith("ies"):
        return w[:-3] + "y"
    if w.endswith("oes"):
        return w[:-2]
    if w.endswith(("ches", "shes", "xes", "sses", "zes")):
        return w[:-2]
    if w.endswith("s"):
        return w[:-1]
    return w


def item_key(name: str, dim: str) -> str:
    """Shopping-list merge key (also the remembered-pick key): singular last word + unit family."""
    words = name.lower().split()
    if words:
        words[-1] = _singular(words[-1])
    return f"{' '.join(words)}|{dim}"


def unit_dim(unit: str | None) -> str:
    """Unit family for merging: "vol", "mass", the count unit itself ("clove", "can"), or "each"."""
    return BASE[unit][0] if unit in BASE else (unit or "each")
