"""Guards for reading untrusted import files: size caps, JSON depth, text decoding. Every failure is one of our
exceptions (ImportTooBig → 413, Unreadable → 422); nothing here fetches anything."""
import json

MAX_DEPTH = 64


class ImportTooBig(Exception):
    pass


class Unreadable(ValueError):
    pass


CHUNK = 64 * 1024


def read_capped(f, max_bytes: int) -> bytes:
    """Read a file object in 64 KB chunks, stopping as soon as it is over max_bytes (ImportTooBig). Whatever a
    Content-Length header claims is not trusted."""
    buf, n = [], 0
    while True:
        chunk = f.read(CHUNK)
        if not chunk:
            return b"".join(buf)
        n += len(chunk)
        if n > max_bytes:
            raise ImportTooBig(f"over {max_bytes} bytes")
        buf.append(chunk)


def too_deep(text: str, limit: int = MAX_DEPTH) -> bool:
    """Bracket nesting deeper than limit (strings skipped): checked before json.loads, which would recurse."""
    depth, in_str, esc = 0, False, False
    for ch in text:
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch in "[{":
            depth += 1
            if depth > limit:
                return True
        elif ch in "]}":
            depth -= 1
    return False


def loads_limited(text: str):
    """json.loads with a nesting limit; any failure → Unreadable."""
    if too_deep(text):
        raise Unreadable("This file is nested too deeply to read.")
    try:
        return json.loads(text)
    except (ValueError, RecursionError) as e:
        raise Unreadable("This file isn't valid JSON.") from e


def decode(data: bytes) -> str:
    """UTF-8 (BOM stripped), else cp1252 with replacement characters. Never raises."""
    if data.startswith(b"\xef\xbb\xbf"):
        data = data[3:]
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("cp1252", errors="replace")
