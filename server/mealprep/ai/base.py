import json, re
from typing import Protocol


class AIError(Exception):
    pass


class Provider(Protocol):
    def complete_json(self, prompt: str, images: list[str] | None = None) -> dict | list: ...


def describe_error(e: Exception) -> str:
    """Safe one-line description of a provider error: status code / exception type only,
    never the request URL or headers (which may carry credentials)."""
    resp = getattr(e, "response", None)
    if resp is not None and getattr(resp, "status_code", None):
        return f"HTTP {resp.status_code}"
    return type(e).__name__


def parse_json(text: str):
    m = re.search(r"```(?:json)?\s*(.*?)```", text, re.S)
    candidates = [m.group(1)] if m else []
    candidates.append(text)
    for c in candidates:
        c = c.strip()
        start = min([i for i in (c.find("{"), c.find("[")) if i >= 0], default=-1)
        if start < 0:
            continue
        try:
            obj, _ = json.JSONDecoder().raw_decode(c[start:])
            return obj
        except json.JSONDecodeError:
            continue
    raise AIError(f"no JSON in model output: {text[:200]!r}")
