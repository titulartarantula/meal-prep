import base64, mimetypes, time
import anthropic
from .base import AIError, parse_json

# Streamed, so a long answer (a week's prep plan, a document's recipes) never hits an HTTP timeout. The model
# thinks (adaptive) before it answers, and the thinking counts against max_tokens, so leave plenty of room.
MAX_TOKENS = 64000
_now = time.monotonic


class AnthropicApi:
    """Anthropic Messages API with an API key (pay-per-use; claude-cli is the subscription route)."""

    def __init__(self, api_key: str, model: str, timeout: int = 180, max_tokens: int = MAX_TOKENS,
                 client: anthropic.Anthropic | None = None):
        self.model, self.timeout, self.max_tokens = model, timeout, max_tokens
        # The SDK retries connection errors, 408/409/429 and 5xx (incl. 529 overloaded) itself.
        self.client = client or anthropic.Anthropic(api_key=api_key, max_retries=3)

    def complete_json(self, prompt: str, images: list[str] | None = None):
        content = []
        for path in images or []:
            mime = mimetypes.guess_type(path)[0] or "image/jpeg"
            with open(path, "rb") as f:
                content.append({"type": "image", "source": {"type": "base64", "media_type": mime,
                                                            "data": base64.standard_b64encode(f.read()).decode()}})
        content.append({"type": "text", "text": prompt})
        # timeout is the whole call's budget (as for the CLI), not the SDK's per-read timeout
        deadline = _now() + self.timeout
        try:
            with self.client.messages.stream(model=self.model, max_tokens=self.max_tokens,
                                             thinking={"type": "adaptive"},
                                             messages=[{"role": "user", "content": content}],
                                             timeout=self.timeout) as stream:
                for _ in stream:
                    if _now() > deadline:
                        raise AIError("Anthropic timed out")
                message = stream.get_final_message()
        except anthropic.APIStatusError as e:
            raise AIError(f"Anthropic error: HTTP {e.status_code}") from e
        except anthropic.APIError as e:              # connection errors, timeouts, broken streams
            raise AIError(f"Anthropic error: {type(e).__name__}") from e
        if message.stop_reason == "refusal":
            raise AIError("Anthropic declined the request")
        if message.stop_reason == "max_tokens":
            raise AIError("Anthropic's answer was cut off (too long)")
        return parse_json("".join(b.text for b in message.content if b.type == "text"))
