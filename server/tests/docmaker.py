"""Builds small PDF and Word (.docx) files from invented text for the document import tests, so no binary fixture
is committed."""
import io
import zipfile


def _pdf(objects: list[bytes]) -> bytes:
    """A PDF from numbered object bodies (object 1 = the catalog), with a correct xref table."""
    out = io.BytesIO()
    out.write(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for i, body in enumerate(objects, 1):
        offsets.append(out.tell())
        out.write(b"%d 0 obj\n" % i + body + b"\nendobj\n")
    xref = out.tell()
    out.write(b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1))
    for off in offsets:
        out.write(b"%010d 00000 n \n" % off)
    out.write(b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, xref))
    return out.getvalue()


def _stream(content: bytes, extra: bytes = b"") -> bytes:
    return b"<< /Length %d %s>>\nstream\n" % (len(content), extra) + content + b"\nendstream"


def _esc(line: str) -> bytes:
    raw = line.encode("cp1252")
    return raw.replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)")


def make_pdf(pages: list[list[str]], images: set[int] = frozenset()) -> bytes:
    """A text PDF: one list of lines per page (Helvetica, WinAnsi). Pages whose index is in `images` hold only a
    picture (no text), like a scanned page inside a typed document."""
    n = len(pages)
    # 1 catalog, 2 pages, 3 font, 4 image, then (page, contents) per page
    kids = b" ".join(b"%d 0 R" % (5 + 2 * i) for i in range(n))
    objs = [b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [%s] /Count %d >>" % (kids, n),
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
            _stream(bytes(range(0, 256, 4)), b"/Type /XObject /Subtype /Image /Width 8 /Height 8 "
                                             b"/ColorSpace /DeviceGray /BitsPerComponent 8 ")]
    for i, lines in enumerate(pages):
        if i in images:
            content = b"q 400 0 0 500 100 150 cm /Im1 Do Q"
        else:
            content = b"BT /F1 11 Tf 72 740 Td 14 TL " + b" ".join(b"(" + _esc(l) + b") Tj T*" for l in lines) + b" ET"
        objs.append(b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> "
                    b"/XObject << /Im1 4 0 R >> >> /Contents %d 0 R >>" % (6 + 2 * i))
        objs.append(_stream(content))
    return _pdf(objs)


def make_scanned_pdf(n_pages: int, size=(612, 792)) -> bytes:
    """A PDF whose pages are pictures only (what a scanner makes)."""
    return make_pdf([[]] * n_pages, images=set(range(n_pages))) if size == (612, 792) else \
        make_pdf([[]] * n_pages, images=set(range(n_pages))).replace(
            b"/MediaBox [0 0 612 792]", b"/MediaBox [0 0 %d %d]" % size)


def make_locked_pdf() -> bytes:
    """A PDF behind the standard security handler with a user password (its O/U entries match no password, so
    opening it without one fails the way a real locked PDF does)."""
    pdf = make_pdf([["Test Locked Lentils", "1 cup lentils", "Simmer gently until soft."]])
    enc = b"99 0 obj\n<< /Filter /Standard /V 1 /R 2 /O (" + b"o" * 32 + b") /U (" + b"u" * 32 + b") /P -44 >>\nendobj"
    body, tail = pdf.split(b"\nxref\n", 1)
    file_id = b"<00112233445566778899aabbccddeeff>"
    return body + b"\n" + enc + b"\nxref\n" + tail.replace(
        b"/Root 1 0 R", b"/Root 1 0 R /Encrypt 99 0 R /ID [" + file_id + file_id + b"]")


W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"


def _xml_escape(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def para(text: str = "", bullet: bool = False) -> str:
    """One Word paragraph; a tab in the text becomes <w:tab/>, a newline <w:br/>."""
    runs = []
    for i, part in enumerate(text.split("\n")):
        if i:
            runs.append("<w:br/>")
        for j, bit in enumerate(part.split("\t")):
            if j:
                runs.append("<w:tab/>")
            if bit:
                runs.append(f'<w:t xml:space="preserve">{_xml_escape(bit)}</w:t>')
    ppr = '<w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>' if bullet else ""
    return f"<w:p>{ppr}<w:r>{''.join(runs)}</w:r></w:p>"


def table(rows: list[list[str]]) -> str:
    cells = "".join("<w:tr>" + "".join(f"<w:tc>{para(c)}</w:tc>" for c in r) + "</w:tr>" for r in rows)
    return f"<w:tbl>{cells}</w:tbl>"


def document_xml(body: str, prolog: str = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>') -> str:
    return f'{prolog}<w:document xmlns:w="{W_NS}"><w:body>{body}<w:sectPr/></w:body></w:document>'


def make_docx(body: str | None = None, *, xml: bytes | None = None, extra: dict[str, bytes] | None = None,
              lines: list[str] | None = None) -> bytes:
    """A .docx zip: `lines` as plain paragraphs, or a `body` of w: markup, or the raw document.xml `xml`."""
    if xml is None:
        if body is None:
            body = "".join(para(l) for l in lines or [])
        xml = document_xml(body).encode()
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("[Content_Types].xml", '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/'
                    'package/2006/content-types"/>')
        zf.writestr("word/document.xml", xml)
        for name, data in (extra or {}).items():
            zf.writestr(name, data)
    return out.getvalue()
