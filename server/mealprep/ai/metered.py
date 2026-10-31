"""Per-household AI metering and the daily budget (server task 2; "ready for a paid service later").

Metered wraps a provider for one household and feature: every complete_json call is logged in ai_usage (tokens when
the provider reports them: AnthropicApi.last_usage) and refused with AIError once the household has made its
households.ai_daily_budget calls in the last 24 hours (null = no limit). Callers already treat AIError as "the AI
couldn't help" (fall-backs, failed jobs), so a spent budget degrades like an AI outage. Metering never breaks a call:
a database error while logging or checking is logged and ignored.
"""
import logging, time

from .base import AIError

log = logging.getLogger(__name__)
BUDGET_SPENT = "This household has used its AI allowance for today; try again tomorrow."


class Meter:
    """Where the rows go: `connect()` returns a connection context (a fresh one per call in the service, so a
    background job's transaction never holds them)."""

    def __init__(self, connect, provider_name: str = ""):
        self.connect, self.provider_name = connect, provider_name

    def spent(self, household_id: int) -> bool:
        try:
            with self.connect() as c:
                row = c.execute("SELECT h.ai_daily_budget, (SELECT count(*) FROM ai_usage u WHERE u.household_id = h.id "
                                "AND u.at > now() - interval '24 hours') FROM households h WHERE h.id=%s",
                                (household_id,)).fetchone()
        except Exception:
            log.exception("ai budget check failed (allowed)")
            return False
        return bool(row) and row[0] is not None and row[1] >= row[0]

    def record(self, household_id, member_id, feature, model, usage, ok: bool, seconds: float) -> None:
        tin = getattr(usage, "input_tokens", None) if usage is not None else None
        tout = getattr(usage, "output_tokens", None) if usage is not None else None
        try:
            with self.connect() as c:
                c.execute("INSERT INTO ai_usage(household_id, member_id, feature, provider, model, input_tokens, "
                          "output_tokens, ok, seconds) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                          (household_id, member_id, feature, self.provider_name, model,
                           tin if isinstance(tin, int) else None, tout if isinstance(tout, int) else None, ok,
                           round(seconds, 2)))
        except Exception:
            log.exception("ai usage record failed (ignored)")


class Metered:
    def __init__(self, inner, meter: Meter, household_id: int, member_id: int | None, feature: str):
        self.inner, self.meter = inner, meter
        self.household_id, self.member_id, self.feature = household_id, member_id, feature

    def complete_json(self, prompt: str, images: list[str] | None = None):
        if self.meter.spent(self.household_id):
            raise AIError(BUDGET_SPENT)
        start, ok = time.monotonic(), False
        try:
            out = self.inner.complete_json(prompt, images)
            ok = True
            return out
        finally:
            model = getattr(self.inner, "model", None)
            self.meter.record(self.household_id, self.member_id, self.feature, model if isinstance(model, str) else None,
                              getattr(self.inner, "last_usage", None) if ok else None, ok, time.monotonic() - start)

    def __getattr__(self, name):        # last_usage, timeout, model … as the wrapped provider has them
        return getattr(self.inner, name)
