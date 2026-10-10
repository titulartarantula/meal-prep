"""Recipe documents (PDF, Word .docx, plain text): what kind of file an upload is (by its content, never its name or
MIME type) and the text in it, or, for a scanned PDF, its pages as images. Pure and capped: nothing is fetched, and
every failure is Unreadable (422) with a sentence the app can show.

Word files are read with the standard library (zipfile + ElementTree): bounded entry count and inflated size, no DTDs
or entities. PDFs are read with PDFium (pypdfium2), which is not thread-safe, so every use holds one lock. A PDF whose
text layer is (almost) empty is a scan: its pages are rendered in grayscale at about 150 dpi (longest side capped)
and written as PNG with a tiny stdlib encoder (no Pillow)."""
from dataclasses import dataclass, field
import io
import re
import struct
import threading
import zipfile
import zlib
from xml.etree import ElementTree

from .safe import Unreadable, decode

NOT_A_RECIPE_FILE = ("This file can't be imported. Choose a recipe document (PDF, Word or text), a recipe file (.json) "
                     "or a saved web page (.html).")
LOCKED = "This PDF is locked with a password. Save a copy without the password and import that."
OLD_WORD = "This is an old Word file (.doc). Save it as .docx or PDF, then import that."
PHOTO = "This looks like a photo. To add a recipe from photos, use Add recipe → Choose photos."
NO_TEXT = "There's no recipe text in this document."
DAMAGED = "Couldn't read this document. It may be damaged: save or export it again, then import the new copy."
TOO_BIG_INSIDE = "This document is too big to read in one go. Split it into smaller files and import each one."

MAX_TEXT = 200_000          # characters of document text read (more → the first part + a warning)
MIN_TEXT = 30               # visible characters a document needs to possibly hold a recipe
MAX_PDF_PAGES = 300
MAX_SCANNED_PAGES = 30
SCANNED_TOTAL = 60          # a PDF with fewer visible characters than this in all, or
SCANNED_PER_PAGE = 25       # fewer than this per page on average, is a scan
RENDER_DPI = 150
MAX_SIDE = 2000             # pixels: a page box of any size renders to at most this
MAX_ENTRIES = 2000          # zip entries in a .docx
MAX_XML = 20 * 1024 * 1024  # word/document.xml, inflated

_W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
_PDFIUM = threading.Lock()


@dataclass
class Document:
    kind: str                                      # "pdf" | "docx" | "text"
    text: str = ""                                 # the text, for everything but a scan
    pages: list[bytes] = field(default_factory=list)   # PNG page images of a scanned PDF
    warnings: list[str] = field(default_factory=list)

    @property
    def scanned(self) -> bool:
        return bool(self.pages)


# --- what is it -----------------------------------------------------------------------------------------------------

_IMAGES = (b"\xff\xd8\xff", b"\x89PNG\r\n\x1a\n", b"GIF87a", b"GIF89a")


def sniff(data: bytes) -> str:
    """"pdf" | "docx" | "json" | "html" | "text", from the bytes. Other binary files → Unreadable with what to do."""
    head = data[:1024]
    if b"%PDF-" in head:
        return "pdf"
    if data.startswith(b"PK\x03\x04"):
        if _is_docx(data):
            return "docx"
        raise Unreadable(NOT_A_RECIPE_FILE)
    if data.startswith(b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"):
        raise Unreadable(OLD_WORD)
    if data.startswith(_IMAGES) or (data[4:8] == b"ftyp" and data[8:12] in (b"heic", b"heix", b"mif1", b"avif")) or \
            (data.startswith(b"RIFF") and data[8:12] == b"WEBP"):
        raise Unreadable(PHOTO)
    if data.startswith(b"\x1f\x8b"):
        raise Unreadable(NOT_A_RECIPE_FILE)
    text = text_of(data)
    if text is None:
        raise Unreadable(NOT_A_RECIPE_FILE)
    start = text.lstrip()[:1]
    if not start:
        raise Unreadable(NOT_A_RECIPE_FILE)
    if start in ("{", "["):
        return "json"
    if re.search(r"<script", text, re.I) or re.match(r"\s*(?:<!--.*?-->\s*)*<(?:!doctype\s+html|html)\b", text, re.I | re.S):
        return "html"   # a saved web page: its JSON-LD or nothing (the page's markup isn't read as a document)
    return "text"


def _is_docx(data: bytes) -> bool:
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            return "word/document.xml" in zf.namelist()
    except Exception:   # BadZipFile, truncated archives …
        return False


def looks_like_json(text: str) -> bool:
    """Broken JSON stays a JSON error ("export it again"); text that merely starts with a bracket is a document."""
    return re.search(r'"[^"\n]{1,80}"\s*:', text[:5000]) is not None


# --- text -------------------------------------------------------------------------------------------------------------

def text_of(data: bytes) -> str | None:
    """Decoded text (UTF-16 with a BOM, UTF-8, else cp1252), or None for binary content (NULs, control characters)."""
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        try:
            text = data.decode("utf-16")
        except UnicodeDecodeError:
            return None
    else:
        if b"\x00" in data[:65536]:
            return None
        text = decode(data)
    sample = text[:65536]
    bad = sum(1 for ch in sample if (ord(ch) < 32 and ch not in "\t\n\r\f") or ch == "�")
    if sample and bad / len(sample) > 0.02:
        return None
    return text


def tidy_text(text: str) -> str:
    """Line endings normalised, form feeds as paragraph breaks, control characters and trailing spaces gone, runs of
    blank lines collapsed."""
    text = text.lstrip("\ufeff").replace("\r\n", "\n").replace("\r", "\n").replace("\f", "\n\n").replace("\u2028", "\n")
    text = "".join(ch for ch in text if ch in "\t\n" or ord(ch) >= 32)
    text = "\n".join(line.rstrip() for line in text.split("\n"))
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def visible(text: str) -> int:
    return sum(1 for ch in text if not ch.isspace())


def _capped(doc: Document) -> Document:
    if len(doc.text) > MAX_TEXT:
        cut = doc.text.rfind("\n", 0, MAX_TEXT)
        doc.text = doc.text[: cut if cut > MAX_TEXT // 2 else MAX_TEXT]
        doc.warnings.append("Only the first part of this document was read (it's very long). Split it into smaller "
                            "files to import the rest.")
    if not doc.scanned and visible(doc.text) < MIN_TEXT:
        raise Unreadable(NO_TEXT)
    return doc


def read_text(data: bytes) -> Document:
    text = text_of(data)
    if text is None:
        raise Unreadable(NOT_A_RECIPE_FILE)
    return _capped(Document("text", tidy_text(text)))


# --- Word (.docx) -------------------------------------------------------------------------------------------------

def _member(zf: zipfile.ZipFile, info: zipfile.ZipInfo, cap: int) -> bytes:
    """An entry inflated in chunks, stopped as soon as it passes the cap (whatever its header claims)."""
    buf = bytearray()
    with zf.open(info) as f:
        while chunk := f.read(64 * 1024):
            buf += chunk
            if len(buf) > cap:
                raise Unreadable(TOO_BIG_INSIDE)
    return bytes(buf)


def _para(p) -> str:
    out = []
    for el in p.iter():
        tag = el.tag
        if tag == _W + "t" and el.text:
            out.append(el.text)
        elif tag == _W + "tab":
            out.append("\t")
        elif tag in (_W + "br", _W + "cr"):
            out.append("\n")
        elif tag == _W + "noBreakHyphen":
            out.append("-")
    text = "".join(out)
    style = p.find(f"{_W}pPr/{_W}pStyle")
    listed = p.find(f"{_W}pPr/{_W}numPr") is not None or (
        style is not None and (style.get(_W + "val") or "").lower().startswith("list"))
    if listed and text.strip():
        text = "• " + text
    return text


def _block(el, out: list[str], depth: int = 0) -> None:
    if depth > 32:
        return
    if el.tag == _W + "p":
        out.append(_para(el))
    elif el.tag == _W + "tbl":
        for tr in el.findall(_W + "tr"):
            cells = [" ".join(t for t in (_para(p).strip() for p in tc.iter(_W + "p")) if t)
                     for tc in tr.findall(_W + "tc")]
            if any(cells):
                out.append(" | ".join(cells))
        out.append("")
    elif el.tag in (_W + "sdt", _W + "sdtContent", _W + "customXml", _W + "body"):
        for child in el:
            _block(child, out, depth + 1)


def read_docx(data: bytes) -> Document:
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            infos = zf.infolist()
            if len(infos) > MAX_ENTRIES:
                raise Unreadable(DAMAGED)
            try:
                info = zf.getinfo("word/document.xml")
            except KeyError:
                raise Unreadable(NOT_A_RECIPE_FILE)
            xml = _member(zf, info, MAX_XML)
    except Unreadable:
        raise
    except Exception as e:   # BadZipFile, encrypted entries, odd compression, truncated data
        raise Unreadable(DAMAGED) from e
    # Word writes UTF-8 and never a DTD. Refusing NULs (UTF-16 would hide the markers below) and any DOCTYPE / ENTITY
    # before parsing leaves ElementTree nothing to expand or resolve (no billion laughs, no external entities).
    if b"\x00" in xml[:512] or b"<!DOCTYPE" in xml or b"<!ENTITY" in xml:
        raise Unreadable(DAMAGED)
    try:
        root = ElementTree.fromstring(xml)
    except ElementTree.ParseError as e:
        raise Unreadable(DAMAGED) from e
    body = root.find(_W + "body")
    lines: list[str] = []
    if body is not None:
        _block(body, lines)
    return _capped(Document("docx", tidy_text("\n".join(lines))))


# --- PDF ------------------------------------------------------------------------------------------------------------

def png_gray(width: int, height: int, stride: int, buf) -> bytes:
    """An 8-bit grayscale PNG from a row-major buffer (stride bytes per row)."""
    mv = memoryview(buf).cast("B")
    raw = b"".join(b"\x00" + bytes(mv[y * stride: y * stride + width]) for y in range(height))

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


def _render(page) -> bytes | None:
    w, h = page.get_size()
    if not (w > 0 and h > 0):
        return None
    scale = min(RENDER_DPI / 72, MAX_SIDE / max(w, h))
    bitmap = page.render(scale=scale, grayscale=True)
    try:
        if bitmap.n_channels != 1:
            return None
        return png_gray(bitmap.width, bitmap.height, bitmap.stride, bitmap.buffer)
    finally:
        bitmap.close()


def _has_images(page) -> bool:
    import pypdfium2.raw as pdfium_c
    return any(True for _ in page.get_objects(filter=[pdfium_c.FPDF_PAGEOBJ_IMAGE], max_depth=2))


def read_pdf(data: bytes) -> Document:
    import pypdfium2 as pdfium
    with _PDFIUM:
        try:
            pdf = pdfium.PdfDocument(data)
        except pdfium.PdfiumError as e:
            raise Unreadable(LOCKED if "password" in str(e).lower() else DAMAGED) from e
        except Exception as e:
            raise Unreadable(DAMAGED) from e
        try:
            return _read_pdf(pdf)
        except Unreadable:
            raise
        except Exception as e:   # PDFium trouble half way: not a 500
            raise Unreadable(DAMAGED) from e
        finally:
            pdf.close()


def _read_pdf(pdf) -> Document:
    n = len(pdf)
    if n == 0:
        raise Unreadable(NO_TEXT)
    warnings = []
    if n > MAX_PDF_PAGES:
        warnings.append(f"Only the first {MAX_PDF_PAGES} of {n} pages were read.")
    texts: list[str] = []
    for i in range(min(n, MAX_PDF_PAGES)):
        page = pdf[i]
        try:
            tp = page.get_textpage()
            try:
                texts.append(tidy_text(tp.get_text_range()))
            finally:
                tp.close()
        finally:
            page.close()
    total = sum(visible(t) for t in texts)
    if total < SCANNED_TOTAL or total / len(texts) < SCANNED_PER_PAGE:
        return _scanned(pdf, n, warnings)
    skipped = 0
    for i, t in enumerate(texts):
        if visible(t) < 10:
            page = pdf[i]
            try:
                skipped += _has_images(page)
            finally:
                page.close()
    if skipped:
        warnings.append(f"{skipped} page{'s' if skipped > 1 else ''} had no text (a scan or a picture) and "
                        f"{'were' if skipped > 1 else 'was'} skipped.")
    text = "\n\n".join(t for t in texts if t)
    return _capped(Document("pdf", text, warnings=warnings))


def _scanned(pdf, n: int, warnings: list[str]) -> Document:
    pages = []
    for i in range(min(n, MAX_SCANNED_PAGES)):
        page = pdf[i]
        try:
            png = _render(page)
        finally:
            page.close()
        if png:
            pages.append(png)
    if not pages:
        raise Unreadable(NO_TEXT)
    warnings = [w for w in warnings if not w.startswith("Only the first")]
    if n > MAX_SCANNED_PAGES:
        warnings.append(f"This PDF is a scan: only its first {MAX_SCANNED_PAGES} of {n} pages were read.")
    return Document("pdf", "", pages=pages, warnings=warnings)


def read(data: bytes, kind: str) -> Document:
    """The document's text (or scanned pages) for a kind from sniff(): "pdf" | "docx" | "text"."""
    if kind == "pdf":
        return read_pdf(data)
    if kind == "docx":
        return read_docx(data)
    return read_text(data)
