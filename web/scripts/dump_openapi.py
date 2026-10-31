"""Write the server's OpenAPI schema to web/openapi.json (no database or network needed).

Run with the server's venv:  ../server/.venv/bin/python scripts/dump_openapi.py   then  npm run gen:api
"""
import json
import pathlib

from mealprep import api
from mealprep.config import Settings

stub = object()   # create_app touches none of these until a request comes in
app = api.create_app(Settings(), provider=stub, pcx=stub, conn=stub, book_search=stub)
out = pathlib.Path(__file__).resolve().parent.parent / "openapi.json"
out.write_text(json.dumps(app.openapi(), indent=1) + "\n")
print(f"{out}: {len(app.openapi()['paths'])} paths")
