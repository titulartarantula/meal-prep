import json, re
from fractions import Fraction
from ..models import Ingredient

_VULGAR = {"½": "1/2", "⅓": "1/3", "⅔": "2/3", "¼": "1/4", "¾": "3/4", "⅕": "1/5", "⅙": "1/6",
           "⅛": "1/8", "⅜": "3/8", "⅝": "5/8", "⅞": "7/8"}
_UNITS = {"teaspoon": "tsp", "teaspoons": "tsp", "tsp": "tsp", "tablespoon": "tbsp", "tablespoons": "tbsp", "tbsp": "tbsp", "tb": "tbsp", "tbs": "tbsp", "tbsps": "tbsp",
          "cup": "cup", "cups": "cup", "gram": "g", "grams": "g", "g": "g", "kilogram": "kg", "kilograms": "kg", "kg": "kg",
          "ml": "ml", "milliliter": "ml", "milliliters": "ml", "millilitre": "ml", "millilitres": "ml",
          "l": "l", "liter": "l", "liters": "l", "litre": "l", "litres": "l",
          "pound": "lb", "pounds": "lb", "lb": "lb", "lbs": "lb", "ounce": "oz", "ounces": "oz", "oz": "oz",
          "each": None, "whole": None, "": None}
_NUM = re.compile(r"\d+\s+\d+/\d+|\d+/\d+|\d*\.\d+|\d+")

PROMPT = """Convert each recipe ingredient line into JSON. Return ONLY a JSON array with exactly {n} objects, same order.
Each object: {{"name": canonical grocery item, singular, lowercase, no prep words (e.g. "yellow onion", "chicken thigh"),
"qty": the number as written (string, e.g. "1 1/2") or null, "unit": unit word or "each" or null,
"prep": prep/cut notes or null (e.g. "diced", "boneless skinless"),
"likely_on_hand": true for pantry staples most kitchens keep (salt, pepper, oil, common dried spices, flour, sugar, butter, eggs) }}
Lines:
{lines}"""


def parse_qty(s) -> float | None:
    """'1 1/2' → 1.5, '½' → 0.5, '2 to 3' → 3 (ranges take the upper bound), 'a pinch' → None."""
    if s is None:
        return None
    if isinstance(s, (int, float)) and not isinstance(s, bool):
        return float(s)
    s = str(s).strip()
    for k, v in _VULGAR.items():
        s = s.replace(k, f" {v}")
    nums = [float(sum(Fraction(p) for p in tok.split())) for tok in _NUM.findall(s)]
    if not nums:
        return None
    if len(nums) >= 2 and re.search(r"\bto\b|-|–|—", s):
        return max(nums)
    return nums[0]


def _unit(u):
    u = (u or "").strip().lower().rstrip(".")
    return _UNITS.get(u, u or None)


_PAGE_REF = re.compile(r"\bpages?\s+(\d{1,4})\b", re.I)


def _ref_page(line: str) -> int | None:
    m = _PAGE_REF.search(line)
    return int(m.group(1)) if m else None


def structure_ingredients(provider, lines: list[str]) -> list[Ingredient]:
    return [i.model_copy(update={"ref_page": _ref_page(i.raw)}) for i in _structure(provider, lines)]


def _structure(provider, lines: list[str]) -> list[Ingredient]:
    if not lines:
        return []
    out = provider.complete_json(PROMPT.format(n=len(lines), lines=json.dumps(lines, ensure_ascii=False)))
    if not isinstance(out, list) or len(out) != len(lines):
        return [Ingredient(raw=l, name=l.lower().strip()) for l in lines]
    res = []
    for l, o in zip(lines, out):
        if not isinstance(o, dict):
            res.append(Ingredient(raw=l, name=l.lower().strip()))
            continue
        res.append(Ingredient(raw=l, name=str(o.get("name") or l).lower().strip(), qty=parse_qty(o.get("qty")),
                              unit=_unit(o.get("unit")), prep=o.get("prep") or None,
                              likely_on_hand=bool(o.get("likely_on_hand"))))
    return res
