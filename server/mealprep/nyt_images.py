"""Fill in the photo link of NYT Cooking recipes saved before imports kept it (once; safe to re-run).

    python -m mealprep.nyt_images [--dry-run] [--delay SECONDS]

Every household's NYT recipes with a cooking.nytimes.com link and no image: fetch the page (one at a time, DELAY
seconds apart, default 4), read the photo link from its JSON-LD and store it in the recipe's `image`. Only the link
is kept; the photo stays on NYT's servers. A page that fails is reported and left as is.
"""
import argparse, logging, sys, time

from psycopg.types.json import Jsonb

from . import db
from .config import Settings
from .importers.nyt import BASE, fetch_nyt_recipe, parse_nyt_image

log = logging.getLogger(__name__)


def missing(conn) -> list[tuple[int, str]]:
    """(id, link) of NYT recipes without a photo link, every household (the login role sees them all)."""
    return conn.execute(
        "SELECT id, source_url FROM recipes WHERE source_kind = 'nyt' AND source_url LIKE %s "
        "AND coalesce(data->>'image', '') = '' ORDER BY id", (f"{BASE}/recipes/%",)).fetchall()


def set_image(conn, rid: int, image: str) -> None:
    conn.execute("UPDATE recipes SET data = jsonb_set(data, '{image}', %s) WHERE id = %s", (Jsonb(image), rid))


def backfill(conn, delay: float = 4.0, dry_run: bool = False, fetch=fetch_nyt_recipe, sleep=time.sleep) -> dict:
    done, none, failed = [], [], []
    rows = missing(conn)
    for i, (rid, url) in enumerate(rows):
        if i:
            sleep(delay)
        try:
            html, _ = fetch(url)
            image = parse_nyt_image(html)
        except Exception as e:   # NytFetchError, network errors
            failed.append((rid, f"{type(e).__name__}: {str(e)[:200]}"))
            continue
        if not image:
            none.append(rid)
            continue
        if not dry_run:
            set_image(conn, rid, image)
        done.append(rid)
    return {"checked": len(rows), "done": done, "no_image": none, "failed": failed}


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(prog="python -m mealprep.nyt_images", description=__doc__.splitlines()[0])
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--delay", type=float, default=4.0)
    a = p.parse_args(argv[1:])
    with db.connect(Settings().dsn) as conn:
        out = backfill(conn, a.delay, a.dry_run)
    print(f"checked {out['checked']}, {'would set' if a.dry_run else 'set'} {len(out['done'])}: {out['done']}")
    print(f"no photo on the page: {out['no_image']}")
    for rid, why in out["failed"]:
        print(f"failed {rid}: {why}")
    return 1 if out["failed"] else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
