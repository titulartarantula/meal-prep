import json, subprocess, pytest
from mealprep.ai import get_provider
from mealprep.ai.base import parse_json, AIError
from mealprep.ai.claude_cli import ClaudeCli
from mealprep.config import Settings


def test_parse_json_plain():
    assert parse_json('{"a": 1}') == {"a": 1}


def test_parse_json_fenced_with_prose():
    assert parse_json('Here you go:\n```json\n[{"a": 1}]\n```\nDone.') == [{"a": 1}]


def test_parse_json_garbage():
    with pytest.raises(AIError):
        parse_json("sorry, I can't read that image")


def test_claude_cli_builds_command_and_parses(monkeypatch):
    seen = {}
    def fake_run(cmd, **kw):
        seen["cmd"] = cmd
        return subprocess.CompletedProcess(cmd, 0, stdout=json.dumps({"result": '```json\n{"ok": true}\n```'}), stderr="")
    monkeypatch.setattr(subprocess, "run", fake_run)
    out = ClaudeCli("/bin/claude", "sonnet").complete_json("hi", images=["/tmp/p1.jpg", "/tmp/p2.jpg"])
    assert out == {"ok": True}
    assert seen["cmd"][:2] == ["/bin/claude", "-p"]
    assert "--allowedTools" in seen["cmd"] and "Read" in seen["cmd"]
    assert "/tmp/p1.jpg" in seen["cmd"][-1] and "/tmp/p2.jpg" in seen["cmd"][-1]
    # --allowedTools is variadic in the real CLI; without "--" it swallows the prompt
    assert seen["cmd"][-2] == "--"


def test_claude_cli_timeout_is_aierror(monkeypatch):
    def boom(cmd, **kw): raise subprocess.TimeoutExpired(cmd, 1)
    monkeypatch.setattr(subprocess, "run", boom)
    with pytest.raises(AIError):
        ClaudeCli("/bin/claude", "sonnet").complete_json("hi")


def test_claude_cli_nonzero_exit_is_aierror(monkeypatch):
    monkeypatch.setattr(subprocess, "run", lambda cmd, **kw: subprocess.CompletedProcess(cmd, 1, stdout="", stderr="not logged in"))
    with pytest.raises(AIError):
        ClaudeCli("/bin/claude", "sonnet").complete_json("hi")


def test_get_provider_selects_by_name():
    assert type(get_provider(Settings(provider="claude-cli"))).__name__ == "ClaudeCli"
    assert type(get_provider(Settings(provider="qwen"))).__name__ == "OpenAICompat"
    assert type(get_provider(Settings(provider="gemini", gemini_key="k"))).__name__ == "Gemini"
    with pytest.raises(ValueError):
        get_provider(Settings(provider="nope"))


def test_gemini_key_sent_in_header_not_url():
    import httpx, respx
    from mealprep.ai.gemini import Gemini
    with respx.mock:
        route = respx.post(url__startswith="https://generativelanguage.googleapis.com/").mock(
            return_value=httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": '{"ok": true}'}]}}]}))
        assert Gemini("SEKRET123", "gemini-2.5-flash").complete_json("hi") == {"ok": True}
        req = route.calls[0].request
        assert "SEKRET123" not in str(req.url)
        assert req.headers["x-goog-api-key"] == "SEKRET123"


def test_gemini_error_message_has_no_key():
    import httpx, respx
    from mealprep.ai.gemini import Gemini
    with respx.mock:
        respx.post(url__startswith="https://generativelanguage.googleapis.com/").mock(return_value=httpx.Response(403, text="denied"))
        with pytest.raises(AIError) as ei:
            Gemini("SEKRET123", "gemini-2.5-flash").complete_json("hi")
        assert "SEKRET123" not in str(ei.value)


def test_claude_cli_text_only_call_gets_no_tools(monkeypatch):
    seen = {}
    def fake_run(cmd, **kw):
        seen["cmd"] = cmd
        return subprocess.CompletedProcess(cmd, 0, stdout=json.dumps({"result": "[]"}), stderr="")
    monkeypatch.setattr(subprocess, "run", fake_run)
    ClaudeCli("/bin/claude", "sonnet").complete_json("structure these lines")
    # model text (e.g. from an NYT page) must not be able to make it read files like ~/meal-prep/.env
    assert "Read" not in seen["cmd"]
    assert seen["cmd"][seen["cmd"].index("--tools") + 1] == ""


def test_openai_official_uses_key_and_completion_tokens(tmp_path):
    import httpx, respx
    from mealprep.ai.openai_compat import OpenAICompat
    img = tmp_path / "p1.jpg"; img.write_bytes(b"\xff\xd8x")
    with respx.mock:
        route = respx.post("https://api.openai.com/v1/chat/completions").mock(return_value=httpx.Response(
            200, json={"choices": [{"message": {"content": '{"ok": true}'}}]}))
        out = OpenAICompat("https://api.openai.com/v1", "some-model", api_key="sk-test", official=True).complete_json("hi", images=[str(img)])
    assert out == {"ok": True}
    req = route.calls[0].request
    body = json.loads(req.content)
    assert req.headers["authorization"] == "Bearer sk-test"
    assert "max_completion_tokens" in body and "max_tokens" not in body and "temperature" not in body
    assert body["messages"][0]["content"][1]["image_url"]["url"].startswith("data:image/jpeg;base64,")


def _sse(texts, stop_reason="end_turn"):
    """A Messages API event stream: a (hidden) thinking block, then one text block per entry of texts."""
    events = [("message_start", {"type": "message_start", "message": {
        "id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-5-5", "content": [],
        "stop_reason": None, "stop_sequence": None, "usage": {"input_tokens": 5, "output_tokens": 1}}}),
        ("content_block_start", {"type": "content_block_start", "index": 0,
                                 "content_block": {"type": "thinking", "thinking": "", "signature": ""}}),
        ("content_block_delta", {"type": "content_block_delta", "index": 0,
                                 "delta": {"type": "signature_delta", "signature": "sig"}}),
        ("content_block_stop", {"type": "content_block_stop", "index": 0})]
    for i, t in enumerate(texts, 1):
        events += [("content_block_start", {"type": "content_block_start", "index": i,
                                            "content_block": {"type": "text", "text": ""}}),
                   ("content_block_delta", {"type": "content_block_delta", "index": i,
                                            "delta": {"type": "text_delta", "text": t}}),
                   ("content_block_stop", {"type": "content_block_stop", "index": i})]
    events += [("message_delta", {"type": "message_delta", "delta": {"stop_reason": stop_reason, "stop_sequence": None},
                                  "usage": {"output_tokens": 9}}),
               ("message_stop", {"type": "message_stop"})]
    return "".join(f"event: {e}\ndata: {json.dumps(d)}\n\n" for e, d in events).encode()


def _anthropic(handler, key="sk-ant-test", **kw):
    import anthropic, httpx2
    from mealprep.ai.anthropic_api import AnthropicApi
    client = anthropic.Anthropic(api_key=key, max_retries=0,
                                 http_client=anthropic.DefaultHttpxClient(transport=httpx2.MockTransport(handler)))
    return AnthropicApi(key, "claude-sonnet-5-5", client=client, **kw)


def _stream(body):
    import httpx2
    return httpx2.Response(200, headers={"content-type": "text/event-stream"}, content=body)


def test_anthropic_api_request_shape_and_parse(tmp_path):
    img = tmp_path / "p1.png"; img.write_bytes(b"\x89PNG")
    seen = []

    def handler(request):
        seen.append(request)
        return _stream(_sse(['```json\n{"ok": ', '1}\n```']))
    out = _anthropic(handler).complete_json("hi", images=[str(img)])
    assert out == {"ok": 1}                               # text blocks joined, the thinking block left out
    req = seen[0]
    body = json.loads(req.content)
    assert str(req.url) == "https://api.anthropic.com/v1/messages"
    assert req.headers["x-api-key"] == "sk-ant-test" and req.headers["anthropic-version"] == "2023-06-01"
    assert body["stream"] is True and body["model"] == "claude-sonnet-5-5"
    assert body["max_tokens"] == 64000 and body["thinking"] == {"type": "adaptive"}
    assert "temperature" not in body                      # Sonnet 5.x rejects temperature
    blocks = body["messages"][0]["content"]
    assert blocks[0]["type"] == "image" and blocks[0]["source"]["media_type"] == "image/png"
    assert blocks[0]["source"]["data"] == "iVBORw=="
    assert blocks[-1] == {"type": "text", "text": "hi"}


def test_anthropic_error_has_no_key():
    import httpx2
    with pytest.raises(AIError) as ei:
        _anthropic(lambda r: httpx2.Response(401, json={"type": "error", "error": {
            "type": "authentication_error", "message": "invalid x-api-key"}}), key="sk-ant-secret").complete_json("hi")
    assert "sk-ant-secret" not in str(ei.value) and "401" in str(ei.value)


def test_anthropic_cut_off_or_declined_answer_is_an_ai_error():
    with pytest.raises(AIError, match="cut off"):
        _anthropic(lambda r: _stream(_sse(['{"steps": ['], stop_reason="max_tokens"))).complete_json("hi")
    with pytest.raises(AIError, match="declined"):
        _anthropic(lambda r: _stream(_sse([], stop_reason="refusal"))).complete_json("hi")


def test_anthropic_connection_error_is_an_ai_error():
    import httpx2

    def handler(request):
        raise httpx2.ConnectError("down")
    with pytest.raises(AIError, match="APIConnectionError"):
        _anthropic(handler).complete_json("hi")


def test_anthropic_timeout_is_the_whole_call(monkeypatch):
    import mealprep.ai.anthropic_api as mod
    clock = iter([0.0, 5.0])                              # deadline 30 s after the start; later events at 99 s
    monkeypatch.setattr(mod, "_now", lambda: next(clock, 99.0))
    with pytest.raises(AIError, match="timed out"):
        _anthropic(lambda r: _stream(_sse(["{}"])), timeout=30).complete_json("hi")


def test_get_provider_api_options():
    from mealprep.ai.anthropic_api import AnthropicApi
    from mealprep.ai.openai_compat import OpenAICompat
    p = get_provider(Settings(provider="openai", openai_key="k", openai_model="m"))
    assert isinstance(p, OpenAICompat) and p.url == "https://api.openai.com/v1" and p.official
    q = get_provider(Settings(provider="qwen"))
    assert isinstance(q, OpenAICompat) and not q.official
    assert isinstance(get_provider(Settings(provider="anthropic", anthropic_key="k")), AnthropicApi)


def test_get_provider_api_missing_key_is_clear():
    with pytest.raises(ValueError, match="MEALPREP_OPENAI_KEY"):
        get_provider(Settings(provider="openai", openai_key="", openai_model="m"))
    with pytest.raises(ValueError, match="MEALPREP_ANTHROPIC_KEY"):
        get_provider(Settings(provider="anthropic", anthropic_key=""))


def test_get_provider_uses_ai_timeout():
    assert get_provider(Settings(provider="claude-cli", ai_timeout=42)).timeout == 42
    assert get_provider(Settings(provider="qwen", ai_timeout=42)).timeout == 42


def test_get_provider_timeout_override_for_long_jobs():
    s = Settings(provider="claude-cli", ai_timeout=42, prep_timeout=900)
    assert get_provider(s, timeout=s.prep_timeout).timeout == 900
    assert get_provider(s).timeout == 42
