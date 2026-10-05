import base64, mimetypes
import httpx
from .base import AIError, describe_error as _describe, parse_json


class Gemini:
    def __init__(self, api_key: str, model: str, timeout: int = 120):
        self.key, self.model, self.timeout = api_key, model, timeout

    def complete_json(self, prompt: str, images: list[str] | None = None):
        parts = [{"text": prompt}]
        for path in images or []:
            mime = mimetypes.guess_type(path)[0] or "image/jpeg"
            with open(path, "rb") as f:
                parts.append({"inline_data": {"mime_type": mime, "data": base64.b64encode(f.read()).decode()}})
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{self.model}:generateContent"
        try:
            r = httpx.post(url, headers={"x-goog-api-key": self.key}, timeout=self.timeout,
                           json={"contents": [{"parts": parts}], "generationConfig": {"responseMimeType": "application/json"}})
            r.raise_for_status()
            text = r.json()["candidates"][0]["content"]["parts"][0]["text"]
        except (httpx.HTTPError, KeyError, IndexError, ValueError) as e:
            raise AIError(f"Gemini error: {_describe(e)}") from e
        return parse_json(text)
