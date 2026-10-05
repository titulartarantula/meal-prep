import base64, mimetypes
import httpx
from .base import AIError, describe_error, parse_json


class OpenAICompat:
    """OpenAI Chat Completions API: official OpenAI (official=True) or a compatible server (Qwen on LM Studio)."""

    def __init__(self, base_url: str, model: str, api_key: str = "lm-studio", timeout: int = 180,
                 max_tokens: int = 8192, official: bool = False):
        self.url, self.model, self.key, self.timeout = base_url.rstrip("/"), model, api_key, timeout
        self.max_tokens = max_tokens   # qwen3.5 thinking mode needs >= 1024 or content comes back empty
        self.official = official       # current OpenAI models reject max_tokens/temperature

    def complete_json(self, prompt: str, images: list[str] | None = None):
        content = [{"type": "text", "text": prompt}]
        for path in images or []:
            mime = mimetypes.guess_type(path)[0] or "image/jpeg"
            with open(path, "rb") as f:
                b64 = base64.b64encode(f.read()).decode()
            content.append({"type": "image_url", "image_url": {"url": f"data:{mime};base64,{b64}"}})
        try:
            r = httpx.post(f"{self.url}/chat/completions", timeout=self.timeout,
                           headers={"Authorization": f"Bearer {self.key}"},
                           json=self._body(content))
            r.raise_for_status()
            text = r.json()["choices"][0]["message"]["content"] or ""
        except (httpx.HTTPError, KeyError, IndexError, ValueError) as e:
            raise AIError(f"{'OpenAI' if self.official else 'LM Studio'} error: {describe_error(e)}") from e
        return parse_json(text)

    def _body(self, content):
        body = {"model": self.model, "messages": [{"role": "user", "content": content}]}
        if self.official:
            body["max_completion_tokens"] = self.max_tokens
        else:
            body.update(temperature=0, max_tokens=self.max_tokens)
        return body
