"""Cooking times: ISO-8601 durations ("PT1H30M") and free text ("1 hr 15 min") → whole minutes, and back."""
from fractions import Fraction
import re

from ..ingredients import VULGAR

MAX_MINUTES = 60 * 24 * 30          # anything longer than a month is not a cooking time
_ISO = re.compile(r"P(?:(\d+(?:\.\d+)?)W)?(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?"
                  r"(?:(\d+(?:\.\d+)?)S)?)?", re.I)
_NUM = r"\d+\s+\d+/\d+|\d+/\d+|\d*\.\d+|\d+"
_UNIT = {"d": 1440, "day": 1440, "days": 1440, "h": 60, "hr": 60, "hrs": 60, "hour": 60, "hours": 60, "m": 1,
         "min": 1, "mins": 1, "minute": 1, "minutes": 1, "s": 1 / 60, "sec": 1 / 60, "secs": 1 / 60,
         "second": 1 / 60, "seconds": 1 / 60}
_PART = re.compile(rf"({_NUM})(?:\s*(?:-|–|—|to)\s*({_NUM}))?\s*([a-z]+)(?![a-z])\.?")


def _num(s: str) -> float:
    return float(sum(Fraction(p) for p in s.split()))


def _ok(minutes: float) -> int | None:
    m = round(minutes)
    return m if 0 < m <= MAX_MINUTES else None


def parse(v) -> int | None:
    """Minutes from an ISO-8601 duration (PT90M, PT1H30M, P0DT0H20M; seconds round), free text ("20 minutes",
    "1 hr 15 min", "1h30", "1 1/2 hours", "20-25 minutes" → the upper bound) or a number (minutes). 0, negative,
    over a month or nonsense → None. Never raises."""
    try:
        return _parse(v)
    except Exception:   # odd input is just "no time"
        return None


def _parse(v) -> int | None:
    if isinstance(v, bool) or v is None:
        return None
    if isinstance(v, (int, float)):
        return _ok(v)
    if not isinstance(v, str):
        return None
    s = v.strip()
    m = _ISO.fullmatch(s)
    if m:
        w, d, h, mi, sec = (float(x) if x else 0.0 for x in m.groups())
        return _ok(w * 10080 + d * 1440 + h * 60 + mi + sec / 60)
    s = s.lower()
    for k, frac in VULGAR.items():
        s = s.replace(k, f" {frac}")
    s = re.sub(r"(\d)\s+(?=\d+/\d+)", r"\1 ", " ".join(s.split()))
    if re.fullmatch(_NUM, s):
        return _ok(_num(s))
    total, found, last_unit = 0.0, False, None
    for m in _PART.finditer(s):
        unit = _UNIT.get(m.group(3))
        if unit is None:
            continue
        total += _num(m.group(2) or m.group(1)) * unit
        found, last_unit = True, unit
    if not found:
        return None
    tail = re.search(rf"(?:\d|[a-z])\s*({_NUM})\s*$", s)   # "1h30": a bare number after hours = minutes
    if tail and last_unit == 60 and not re.search(r"[a-z]\s*$", s):
        total += _num(tail.group(1))
    return _ok(total)


def to_iso(minutes: int | None) -> str | None:
    """90 → "PT1H30M", 45 → "PT45M", 60 → "PT1H"; None / 0 / negative → None (PT0M is never written)."""
    if minutes is None or minutes <= 0:
        return None
    h, m = divmod(int(minutes), 60)
    return "PT" + (f"{h}H" if h else "") + (f"{m}M" if m else "")
