import base64, mimetypes
import httpx
from .base import AIError, describe_error, parse_json


class AnthropicApi:
    """Anthropic Messages API with an API key (pay-per-use; claude-cli is the subscription route)."""

    def __init__(self, api_key: str, model: str, timeout: int = 180, max_tokens: int = 8192):
        self.key, self.model, self.timeout, self.max_tokens = api_key, model, timeout, max_tokens

    def complete_json(self, prompt: str, images: list[str] | None = None):
        content = []
        for path in images or []:
            mime = mimetypes.guess_type(path)[0] or "image/jpeg"
            with open(path, "rb") as f:
                content.append({"type": "image", "source": {"type": "base64", "media_type": mime,
                                                            "data": base64.b64encode(f.read()).decode()}})
        content.append({"type": "text", "text": prompt})
        try:
            r = httpx.post("https://api.anthropic.com/v1/messages", timeout=self.timeout,
                           headers={"x-api-key": self.key, "anthropic-version": "2023-06-01"},
                           json={"model": self.model, "max_tokens": self.max_tokens,
                                 "messages": [{"role": "user", "content": content}]})
            r.raise_for_status()
            text = "".join(b.get("text", "") for b in r.json()["content"] if b.get("type") == "text")
        except (httpx.HTTPError, KeyError, ValueError) as e:
            raise AIError(f"Anthropic error: {describe_error(e)}") from e
        return parse_json(text)
