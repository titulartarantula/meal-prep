import os
from dataclasses import dataclass, field


def _env(name: str, default: str = ""):
    return field(default_factory=lambda: os.environ.get(name, default))


@dataclass(frozen=True)
class Settings:
    dsn: str = _env("MEALPREP_DSN")
    token: str = field(default_factory=lambda: os.environ.get("MEALPREP_TOKEN", ""), repr=False)
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
    ai_timeout: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_AI_TIMEOUT", "300")))
    # Recipe import: largest upload (MB), AI workers tidying foreign ingredient lines, and each recipe's AI budget (s).
    import_max_mb: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_IMPORT_MAX_MB", "20")))
    import_workers: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_IMPORT_WORKERS", "4")))
    import_ai_timeout: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_IMPORT_AI_TIMEOUT", "180")))
    # Recipe documents: each AI call that finds the recipes in a part of a document (s; the answer is long).
    document_ai_timeout: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_DOCUMENT_AI_TIMEOUT", "600")))
    # Edit recipe: the AI structuring changed ingredient lines while the app waits (s); slower → the deterministic clean-up.
    edit_ai_timeout: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_EDIT_AI_TIMEOUT", "20")))
    # Fridge (0.14.0): mapping a typed name onto the shopping list's names while the app waits (s; slower → the rule),
    # reading fridge photos (s), and the photo limits (6 per read; each photo ≤ 7 MB so its base64 stays under the
    # Anthropic API's 10 MB per image, all together ≤ 20 MB so the request stays under its 32 MB).
    fridge_ai_timeout: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_FRIDGE_AI_TIMEOUT", "20")))
    fridge_photo_timeout: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_FRIDGE_PHOTO_TIMEOUT", "180")))
    fridge_max_photos: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_FRIDGE_MAX_PHOTOS", "6")))
    fridge_photo_max_mb: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_FRIDGE_PHOTO_MAX_MB", "7")))
    fridge_photos_max_mb: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_FRIDGE_PHOTOS_MAX_MB", "20")))
    # Web + households (server tasks 1–2). Google sign-in is off (login routes answer 503) until the client id and
    # secret are set. The redirect URI must be registered on the Google OAuth client exactly as sent.
    google_client_id: str = _env("MEALPREP_GOOGLE_CLIENT_ID")
    google_client_secret: str = field(default_factory=lambda: os.environ.get("MEALPREP_GOOGLE_CLIENT_SECRET", ""),
                                      repr=False)
    public_url: str = _env("MEALPREP_PUBLIC_URL", "https://mealprep.drwhom.ca")
    google_redirect_uri: str = _env("MEALPREP_GOOGLE_REDIRECT_URI")   # default: public_url + /api/auth/callback
    session_days: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_SESSION_DAYS", "30")))
    # The shared bearer token (until the Android release with sign-in) is only accepted from these source networks.
    legacy_networks: str = _env("MEALPREP_LEGACY_NETWORKS", "127.0.0.1/32,192.168.1.0/24,10.8.0.0/24")
    # AI calls a new household may make per rolling 24 h (the first household has no limit).
    ai_daily_budget: int = field(default_factory=lambda: int(os.environ.get("MEALPREP_AI_DAILY_BUDGET", "400")))
    store_id: str = "1092"
    default_people: int = 4
