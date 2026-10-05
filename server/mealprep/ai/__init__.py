from .base import AIError, Provider, parse_json
from .claude_cli import ClaudeCli
from .openai_compat import OpenAICompat
from .gemini import Gemini
from .anthropic_api import AnthropicApi

__all__ = ["AIError", "Provider", "parse_json", "get_provider", "ClaudeCli", "OpenAICompat", "Gemini", "AnthropicApi"]


def get_provider(s, timeout: int | None = None) -> Provider:
    """timeout overrides s.ai_timeout (e.g. the longer prep-plan budget)."""
    t = timeout or s.ai_timeout
    if s.provider == "claude-cli":
        return ClaudeCli(s.claude_bin, s.claude_model, timeout=t)
    if s.provider == "qwen":
        return OpenAICompat(s.qwen_url, s.qwen_model, timeout=t)
    if s.provider == "openai":
        _need(s.openai_key, "MEALPREP_OPENAI_KEY"); _need(s.openai_model, "MEALPREP_OPENAI_MODEL")
        return OpenAICompat(s.openai_url, s.openai_model, api_key=s.openai_key, official=True, timeout=t)
    if s.provider == "anthropic":
        _need(s.anthropic_key, "MEALPREP_ANTHROPIC_KEY")
        return AnthropicApi(s.anthropic_key, s.anthropic_model, timeout=t)
    if s.provider == "gemini":
        _need(s.gemini_key, "MEALPREP_GEMINI_KEY")
        return Gemini(s.gemini_key, s.gemini_model, timeout=t)
    raise ValueError(f"unknown provider {s.provider}")


def _need(value: str, env_name: str) -> None:
    if not value:
        raise ValueError(f"{env_name} is not set")
