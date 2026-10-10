"""Recipe documents: kind by content, text from TXT / DOCX / PDF, scanned PDFs as page images, and the guards.
Every file is built here from invented text (tests/docmaker.py)."""
import io
import struct
import zipfile
import zlib

import pytest
from hypothesis import given, settings, strategies as st

from docmaker import document_xml, make_docx, make_locked_pdf, make_pdf, make_scanned_pdf, para, table
from mealprep.exchange import documents as d
from mealprep.exchange.safe import Unreadable

SOUP = ["Test Barley Soup", "Serves 4", "1 cup pearl barley", "2 carrots, diced", "Simmer everything for 40 minutes."]


# --- sniff ----------------------------------------------------------------------------------------------------------

@pytest.mark.parametrize("data,kind", [
    (b"%PDF-1.7\n...", "pdf"),
    (b"\xef\xbb\xbfjunk before the header\n%PDF-1.4\n", "pdf"),
    (b'{"@type": "Recipe"}', "json"),
    (b"  [1, 2]", "json"),
    (b"<html><script type='application/ld+json'>{}</script></html>", "html"),
    (b"<!DOCTYPE html>\n<html><body>Test Pea Soup, no JSON-LD</body></html>", "html"),
    (b"<!-- saved -->\n<HTML><body>x</body></HTML>", "html"),
    (b"Test Pea Soup\n<b>1 cup</b> peas, a recipe in text with a tag", "text"),
    ("Test Pea Soup\n1 cup peas\nSimmer.".encode(), "text"),
    ("Crème brûlée\n".encode("cp1252"), "text"),
    ("Test Pea Soup\n1 cup peas".encode("utf-16"), "text"),
])
def test_sniff(data, kind):
    assert d.sniff(data) == kind


def test_sniff_docx_and_other_zips():
    assert d.sniff(make_docx(lines=["Test Pea Soup"])) == "docx"
    other = io.BytesIO()
    with zipfile.ZipFile(other, "w") as zf:
        zf.writestr("content.xml", "<x/>")
    with pytest.raises(Unreadable) as e:
        d.sniff(other.getvalue())
    assert str(e.value) == d.NOT_A_RECIPE_FILE
    with pytest.raises(Unreadable):
        d.sniff(b"PK\x03\x04 truncated zip")


@pytest.mark.parametrize("data,message", [
    (b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\0" * 100, d.OLD_WORD),
    (b"\xff\xd8\xff\xe0" + b"\0" * 100, d.PHOTO),
    (b"\x89PNG\r\n\x1a\n" + b"\0" * 100, d.PHOTO),
    (b"\0\0\0\x18ftypheic" + b"\0" * 100, d.PHOTO),
    (b"RIFF\0\0\0\0WEBPVP8 ", d.PHOTO),
    (b"\x1f\x8b\x08\0", d.NOT_A_RECIPE_FILE),
    (b"", d.NOT_A_RECIPE_FILE),
    (b"   \n\t ", d.NOT_A_RECIPE_FILE),
    (b"ELF\x7f\0\0\x01\x02binary", d.NOT_A_RECIPE_FILE),
    (bytes(range(1, 32)) * 20, d.NOT_A_RECIPE_FILE),
])
def test_sniff_refuses_with_a_plain_message(data, message):
    with pytest.raises(Unreadable) as e:
        d.sniff(data)
    assert str(e.value) == message


def test_looks_like_json():
    assert d.looks_like_json('{"name": "Soup", "x": ')
    assert not d.looks_like_json("[Family favourite] Test Pea Soup\n1 cup peas")


# --- text -------------------------------------------------------------------------------------------------------------

def test_text_decoding_and_tidying():
    doc = d.read("Test Pea Soup\r\n\r\n\r\n\r\n1 cup peas   \r\nSimmer.\x0cPage two text line".encode(), "text")
    assert doc.kind == "text" and doc.text == "Test Pea Soup\n\n1 cup peas\nSimmer.\n\nPage two text line"
    assert d.read("Crème brûlée for four people\n2 cups cream".encode("cp1252"), "text").text.startswith("Crème")
    assert d.read("Test Pea Soup with a long enough line".encode("utf-16"), "text").text.startswith("Test Pea")


def test_too_little_text():
    with pytest.raises(Unreadable) as e:
        d.read(b"hello", "text")
    assert str(e.value) == d.NO_TEXT


def test_text_cap_warns(monkeypatch):
    monkeypatch.setattr(d, "MAX_TEXT", 1000)
    doc = d.read(("Test line of a long family cookbook\n" * 200).encode(), "text")
    assert len(doc.text) <= 1000 and doc.text.endswith("cookbook")
    assert doc.warnings and doc.warnings[0].startswith("Only the first part")


# --- Word -----------------------------------------------------------------------------------------------------------

def test_docx_paragraphs_lists_tables_in_order():
    body = (para("Test Bean Stew") + para("Serves 6") + para("1 can white beans", bullet=True)
            + para("2 cloves garlic", bullet=True) + table([["Stock", "2 cups"], ["Thyme", "1 sprig"]])
            + para("Warm the oil.\tStir.\nAdd the beans.") + para("") + para("Test Second Dish"))
    doc = d.read(make_docx(body), "docx")
    assert doc.kind == "docx"
    assert doc.text.split("\n") == ["Test Bean Stew", "Serves 6", "• 1 can white beans", "• 2 cloves garlic",
                                    "Stock | 2 cups", "Thyme | 1 sprig", "", "Warm the oil.\tStir.",
                                    "Add the beans.", "", "Test Second Dish"]


def test_docx_skips_deleted_text_and_reads_content_controls():
    body = ('<w:sdt><w:sdtContent>' + para("Test Inside A Control box") + '</w:sdtContent></w:sdt>'
            '<w:p><w:r><w:t>Kept </w:t></w:r><w:del><w:r><w:delText>gone</w:delText></w:r></w:del>'
            '<w:r><w:t>words in the paragraph</w:t></w:r></w:p>')
    assert d.read(make_docx(body), "docx").text == "Test Inside A Control box\nKept words in the paragraph"


@pytest.mark.parametrize("xml", [
    b'<?xml version="1.0"?><!DOCTYPE w [<!ENTITY a "aaaa">]><w:document xmlns:w="x">&a;</w:document>',
    document_xml(para("x" * 50)).encode("utf-16"),
    b"<w:document><unclosed>",
])
def test_docx_hostile_or_broken_xml(xml):
    with pytest.raises(Unreadable) as e:
        d.read(make_docx(xml=xml), "docx")
    assert str(e.value) == d.DAMAGED


def test_docx_inflated_size_is_capped(monkeypatch):
    monkeypatch.setattr(d, "MAX_XML", 100_000)
    bomb = document_xml(para("Test " + "a" * 2_000_000)).encode()   # deflates to a few KB
    data = make_docx(xml=bomb)
    assert len(data) < 50_000
    with pytest.raises(Unreadable) as e:
        d.read(data, "docx")
    assert str(e.value) == d.TOO_BIG_INSIDE


def test_docx_too_many_entries_and_missing_body(monkeypatch):
    monkeypatch.setattr(d, "MAX_ENTRIES", 5)
    data = make_docx(lines=["Test Pea Soup"], extra={f"word/media/x{i}.bin": b"x" for i in range(10)})
    with pytest.raises(Unreadable):
        d.read(data, "docx")
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as zf:
        zf.writestr("word/other.xml", "<x/>")
    with pytest.raises(Unreadable) as e:
        d.read(out.getvalue(), "docx")
    assert str(e.value) == d.NOT_A_RECIPE_FILE
    with pytest.raises(Unreadable):
        d.read(make_docx(lines=[]), "docx")   # nothing in it


def test_docx_corrupt_entry():
    data = bytearray(make_docx(lines=["Test Pea Soup " * 20]))
    i = data.find(b"word/document.xml") + 40
    data[i:i + 30] = b"\xff" * 30
    with pytest.raises(Unreadable):
        d.read(bytes(data), "docx")


# --- PDF ------------------------------------------------------------------------------------------------------------

def test_pdf_text_pages_in_order():
    pdf = make_pdf([SOUP, ["Test Crème Fraîche Dip", "1 cup crème fraîche", "Stir (gently) and serve."]])
    doc = d.read(pdf, "pdf")
    assert doc.kind == "pdf" and not doc.scanned and doc.warnings == []
    assert doc.text.split("\n") == SOUP + ["", "Test Crème Fraîche Dip", "1 cup crème fraîche",
                                           "Stir (gently) and serve."]


def test_pdf_image_only_page_is_skipped_with_a_warning():
    doc = d.read(make_pdf([SOUP, [], SOUP, []], images={1}), "pdf")
    assert doc.warnings == ["1 page had no text (a scan or a picture) and was skipped."]   # the blank page isn't
    assert doc.text.count("Test Barley Soup") == 2


def _png_size(png: bytes) -> tuple[int, int]:
    assert png.startswith(b"\x89PNG\r\n\x1a\n") and png[12:16] == b"IHDR"
    return struct.unpack(">II", png[16:24])


def test_scanned_pdf_becomes_page_images():
    doc = d.read(make_scanned_pdf(3), "pdf")
    assert doc.scanned and len(doc.pages) == 3 and doc.text == "" and doc.warnings == []
    w, h = _png_size(doc.pages[0])
    assert abs(w - 612 * 150 / 72) <= 1 and abs(h - 792 * 150 / 72) <= 1


def test_scanned_page_size_is_capped():
    doc = d.read(make_scanned_pdf(1, size=(14400, 14400)), "pdf")
    assert max(_png_size(doc.pages[0])) <= d.MAX_SIDE


def test_scanned_page_cap(monkeypatch):
    monkeypatch.setattr(d, "MAX_SCANNED_PAGES", 2)
    doc = d.read(make_scanned_pdf(4), "pdf")
    assert len(doc.pages) == 2 and doc.warnings == ["This PDF is a scan: only its first 2 of 4 pages were read."]


def test_pdf_page_cap(monkeypatch):
    monkeypatch.setattr(d, "MAX_PDF_PAGES", 2)
    doc = d.read(make_pdf([SOUP, SOUP, SOUP]), "pdf")
    assert doc.text.count("Test Barley Soup") == 2 and doc.warnings == ["Only the first 2 of 3 pages were read."]


def test_png_writer_round_trip():
    width, height, stride = 3, 2, 4
    buf = bytes([0, 128, 255, 9, 10, 20, 30, 9])
    png = d.png_gray(width, height, stride, buf)
    assert _png_size(png) == (3, 2)
    idat = png[33:]
    n = struct.unpack(">I", idat[:4])[0]
    assert idat[4:8] == b"IDAT"
    assert zlib.decompress(idat[8:8 + n]) == b"\0" + bytes([0, 128, 255]) + b"\0" + bytes([10, 20, 30])


def test_locked_and_broken_pdfs():
    with pytest.raises(Unreadable) as e:
        d.read(make_locked_pdf(), "pdf")
    assert str(e.value) == d.LOCKED
    with pytest.raises(Unreadable) as e:
        d.read(b"%PDF-1.4\nnot really a pdf at all", "pdf")
    assert str(e.value) == d.DAMAGED
    good = make_pdf([SOUP])
    with pytest.raises(Unreadable):
        d.read(good[: len(good) // 3], "pdf")


@settings(max_examples=150, deadline=None, derandomize=True)
@given(st.binary(max_size=2000))
def test_random_bytes_only_raise_unreadable(data):
    for blob in (data, b"%PDF-1.4\n" + data, b"PK\x03\x04" + data):
        try:
            kind = d.sniff(blob)
            if kind in ("pdf", "docx", "text"):
                d.read(blob, kind)
        except Unreadable:
            pass
