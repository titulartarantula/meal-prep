# run manually: RUN_LIVE=1 pytest tests/test_pcx_live.py — creates a throwaway ANONYMOUS cart (no login, no checkout)
import os, pytest
from mealprep.pcx import Pcx


@pytest.mark.skipif(not os.environ.get("RUN_LIVE"), reason="live")
def test_live_search_and_cart():
    p = Pcx()
    prods = p.search("boneless chicken thighs")
    assert prods
    cid = p.create_cart()
    p.add(cid, {prods[0].code: 1})
    entries = [e for o in p.get_cart(cid)["orders"] for e in o["entries"]]
    assert entries[0]["quantity"] == 1
    print("live cart", cid, prods[0].code, prods[0].name, prods[0].price)
