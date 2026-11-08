"""Where a recipe comes from: exactly three kinds (2026-11-08) — a website ("web": source_title = the site, source_author
= the recipe's author, source_url = its page), a book, or other (family recipes, notes, anything without a link).

NYT Cooking is one website among others: its recipes are stored as kind "web" with the site name NYT_SITE (the NYT
importer still handles its links: slug repair, photo crops). The old kind "nyt" is accepted on input from older apps
and files and read as a website; `python -m mealprep.three_sources` moved the stored rows."""
from urllib.parse import urlsplit

KINDS = ("web", "book", "other")
NYT_SITE = "NYT Cooking"   # the one site name for NYT recipes (the page's og:site_name is "NYT Cooking" too)


def is_nyt_url(url: str | None) -> bool:
    host = (urlsplit(url).hostname or "") if url else ""
    return host == "nytimes.com" or host.endswith(".nytimes.com")


def kind_in(v):
    """An incoming source kind: the retired "nyt" is a website."""
    return "web" if v == "nyt" else v
