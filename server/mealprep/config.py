import os
from dataclasses import dataclass, field


def _env(name: str, default: str = ""):
    return field(default_factory=lambda: os.environ.get(name, default))


@dataclass(frozen=True)
class Settings:
    dsn: str = _env("MEALPREP_DSN")
    token: str = _env("MEALPREP_TOKEN")
    provider: str = _env("MEALPREP_PROVIDER", "claude-cli")
    claude_bin: str = _env("MEALPREP_CLAUDE_BIN", "claude")
    claude_model: str = _env("MEALPREP_CLAUDE_MODEL", "sonnet")
    qwen_url: str = _env("MEALPREP_QWEN_URL", "http://localhost:1234/v1")
    qwen_model: str = _env("MEALPREP_QWEN_MODEL", "qwen/qwen3.5-9b")
    openai_url: str = _env("MEALPREP_OPENAI_URL", "https://api.openai.com/v1")
    openai_key: str = _env("MEALPREP_OPENAI_KEY")
    openai_model: str = _env("MEALPREP_OPENAI_MODEL")
    anthropic_key: str = _env("MEALPREP_ANTHROPIC_KEY")
    anthropic_model: str = _env("MEALPREP_ANTHROPIC_MODEL", "claude-sonnet-5-5")
    gemini_key: str = _env("MEALPREP_GEMINI_KEY")
    gemini_model: str = _env("MEALPREP_GEMINI_MODEL", "gemini-2.5-flash")
    pcx_apikey: str = _env("MEALPREP_PCX_APIKEY")
    google_books_key: str = field(default_factory=lambda: os.environ.get("MEALPREP_GOOGLE_BOOKS_KEY", ""), repr=False)
    match_workers: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_MATCH_WORKERS", "6")))
    prep_workers: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_PREP_WORKERS", "4")))
    ai_timeout: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_AI_TIMEOUT", "300")))
    # A week's prep plan is one long generation: Claude CLI thinks for ~16k tokens (~3 min for 2 recipes).
    prep_timeout: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_PREP_TIMEOUT", "900")))
    store_id: str = "1092"
    default_people: int = 4
